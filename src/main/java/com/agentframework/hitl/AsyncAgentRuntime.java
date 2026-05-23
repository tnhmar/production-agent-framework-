package com.agentframework.hitl;

import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.observability.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous agent runtime with Human-in-the-Loop (HITL) pause/resume.
 *
 * <h3>HITL flow</h3>
 * <ol>
 *   <li>{@link #suspend} starts the agent on the async pool and returns a
 *       {@link CompletableFuture} immediately.</li>
 *   <li>If {@link StateMachineRunner} exits with {@link RunState#SUSPENDED_HITL},
 *       the context is check-pointed, the snapshot is stored in
 *       {@link ExecutionStore} under a new {@link JobToken}, and the future
 *       is parked in {@code pendingFutures}.</li>
 *   <li>The operator calls {@link #resume(JobToken, ApprovalDecision, Agent)},
 *       which loads and integrity-verifies the snapshot, restores the context,
 *       injects the approval decision into working memory, and re-runs.</li>
 *   <li>The original {@link CompletableFuture} is completed when the resumed
 *       run finishes.</li>
 * </ol>
 */
public class AsyncAgentRuntime {

    private final PlanValidator   validator;
    private final EventSink       events;
    private final ExecutionStore  store;
    private final ExecutorService pool;

    private final ConcurrentHashMap<String, CompletableFuture<ExecutionResult>>
        pendingFutures = new ConcurrentHashMap<>();

    public AsyncAgentRuntime(PlanValidator validator, EventSink events, ExecutionStore store) {
        this(validator, events, store, defaultPool());
    }

    public AsyncAgentRuntime(PlanValidator validator, EventSink events,
                              ExecutionStore store, ExecutorService pool) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.events    = Objects.requireNonNull(events,    "events");
        this.store     = Objects.requireNonNull(store,     "store");
        this.pool      = Objects.requireNonNull(pool,      "pool");
    }

    // ── Public API ────────────────────────────────────────────────

    public CompletableFuture<ExecutionResult> suspend(
            Agent agent, Task task, String tenantId, String userId) {
        CompletableFuture<ExecutionResult> future = new CompletableFuture<>();
        CompletableFuture.runAsync(
            () -> runWithHitlSupport(agent, task, tenantId, userId, future),
            pool);
        return future;
    }

    /**
     * Resumes a suspended HITL run.
     *
     * @param token    the token issued when the run was suspended
     * @param decision the operator's approval/rejection/modification
     * @param agent    the agent implementation (same one used in {@link #suspend})
     */
    public CompletableFuture<ExecutionResult> resume(
            JobToken token, ApprovalDecision decision, Agent agent) {

        // 1. Load snapshot — keyed by the token's jobId (the persisted runId)
        ExecutionContext.Snapshot snap = store.load(token.jobId())
            .orElseThrow(() -> new IllegalStateException(
                "No snapshot found for jobToken=" + token.jobId()));

        // 2. Verify integrity before restoring any state
        verifyIntegrity(snap);

        // 3. Restore context
        Task replayTask = buildReplayTask(snap);
        DefaultExecutionContext ctx = new DefaultExecutionContext(
            replayTask, snap.runId(), "hitl-resume-user");
        ctx.restoreFromSnapshot(snap);

        // 4. Inject operator decision as a CLEAN SYSTEM working-memory entry
        String decisionText = switch (decision) {
            case ApprovalDecision.Approved a  -> "HITL_DECISION: APPROVED";
            case ApprovalDecision.Rejected r  -> "HITL_DECISION: REJECTED | " + r.reason();
            case ApprovalDecision.Modified m  -> "HITL_DECISION: MODIFIED | tool=" + m.updatedCall().toolName();
            case ApprovalDecision.Escalated e -> "HITL_DECISION: ESCALATED | " + e.reason();
        };
        ctx.workingMemory().add(new WorkingMemoryEntry(
            UUID.randomUUID().toString(),
            decisionText,
            WorkingMemoryTier.ACTIVE,
            Origin.SYSTEM,
            1.0,
            Instant.now(),
            TaintLabel.CLEAN));

        // 5. Transition to VALIDATING so the state machine re-enters from the right phase
        ctx.transitionTo(RunState.VALIDATING);

        CompletableFuture<ExecutionResult> future =
            pendingFutures.getOrDefault(snap.runId(), new CompletableFuture<>());
        pendingFutures.put(snap.runId(), future);

        CompletableFuture.runAsync(
            () -> runWithHitlSupport(agent, replayTask,
                snap.runId(), "hitl-resume-user", future, ctx),
            pool);
        return future;
    }

    /**
     * Non-blocking status poll for a suspended job.
     */
    public JobStatus query(JobToken token) {
        if (store.load(token.jobId()).isEmpty()) return JobStatus.NOT_FOUND;
        CompletableFuture<ExecutionResult> f = pendingFutures.get(token.jobId());
        if (f == null)  return JobStatus.NOT_FOUND;
        if (f.isDone()) return JobStatus.COMPLETED;
        return JobStatus.PENDING;
    }

    public enum JobStatus { PENDING, COMPLETED, NOT_FOUND }

    // ── Internal helpers ────────────────────────────────────────────

    private void runWithHitlSupport(
            Agent agent, Task task, String tenantId, String userId,
            CompletableFuture<ExecutionResult> future) {
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, tenantId, userId);
        runWithHitlSupport(agent, task, tenantId, userId, future, ctx);
    }

    private void runWithHitlSupport(
            Agent agent, Task task, String tenantId, String userId,
            CompletableFuture<ExecutionResult> future,
            DefaultExecutionContext ctx) {
        try {
            emit(ctx, AgentEvent.EventType.RUN_STARTED,
                Map.of("instruction", task.instruction()));
            new StateMachineRunner(validator, events).run(agent, ctx);

            if (ctx.currentState() == RunState.SUSPENDED_HITL) {
                // Checkpoint, store, and park the future until resume()
                ExecutionContext.Snapshot snap = ctx.checkpoint();
                String tokenId = UUID.randomUUID().toString();
                // ExecutionStore.save() is 1-arg; the snapshot carries its own runId.
                // We store under the generated tokenId by setting snap.runId = tokenId
                // via the store's own keying contract — here we wrap to honour the interface:
                store.save(new ExecutionContext.Snapshot() {
                    public String               runId()                  { return tokenId; }
                    public RunState             state()                  { return snap.state(); }
                    public int                  cycle()                  { return snap.cycle(); }
                    public String               schemaVersion()          { return snap.schemaVersion(); }
                    public List<Goal>           goalStackSnapshot()      { return snap.goalStackSnapshot(); }
                    public List<WorkingMemoryEntry> workingMemorySnapshot() { return snap.workingMemorySnapshot(); }
                    public List<Belief>         beliefSnapshot()         { return snap.beliefSnapshot(); }
                    public int                  totalTokens()            { return snap.totalTokens(); }
                    public java.math.BigDecimal totalCost()              { return snap.totalCost(); }
                    public int                  consecutiveFailures()    { return snap.consecutiveFailures(); }
                    public int                  stagnantCycles()         { return snap.stagnantCycles(); }
                    public int                  stuckCycles()            { return snap.stuckCycles(); }
                    public int                  revisionCount()          { return snap.revisionCount(); }
                    public String               integrityHash()          { return snap.integrityHash(); }
                });
                // 3-field JobToken: jobId, statusEndpoint, estimatedDuration
                JobToken token = new JobToken(
                    tokenId,
                    "/api/v1/jobs/" + tokenId + "/status",
                    Duration.ofMinutes(30));
                ctx.activeJobs().put(tokenId, token);
                pendingFutures.put(ctx.runId(), future);
                emit(ctx, AgentEvent.EventType.HITL_REQUESTED,
                    Map.of("jobToken", tokenId));
                // Future stays pending — completed in resume()
            } else {
                emit(ctx, AgentEvent.EventType.RUN_COMPLETED,
                    Map.of("state", ctx.currentState().name()));
                future.complete(ExecutionResult.from(ctx));
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    private void verifyIntegrity(ExecutionContext.Snapshot snapshot) {
        String expected = DefaultExecutionContext.computeSnapshotHash(snapshot);
        if (!expected.equals(snapshot.integrityHash())) {
            throw new IllegalArgumentException(
                "HITL resume: snapshot integrity check failed for runId="
                + snapshot.runId() + " at cycle=" + snapshot.cycle()
                + ". Expected=" + expected
                + ", stored=" + snapshot.integrityHash());
        }
    }

    private Task buildReplayTask(ExecutionContext.Snapshot snap) {
        String instruction = snap.goalStackSnapshot().stream()
            .filter(g -> "root".equals(g.id()))
            .findFirst()
            .map(Goal::description)
            .orElse("(hitl-resume — instruction unavailable)");
        return Task.builder()
            .instruction(instruction)
            .maxCycles(Integer.MAX_VALUE / 2)
            .maxTokens(Integer.MAX_VALUE / 2)
            .build();
    }

    private void emit(ExecutionContext ctx, AgentEvent.EventType type,
                      Map<String, Object> attrs) {
        events.emit(new AgentEvent(
            ctx.runId(), ctx.tenantId(), type, Instant.now(), attrs));
    }

    private static ExecutorService defaultPool() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-hitl-async");
            t.setDaemon(true);
            return t;
        });
    }
}
