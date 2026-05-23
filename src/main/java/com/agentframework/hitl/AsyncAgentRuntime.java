package com.agentframework.hitl;

import com.agentframework.core.Agent;
import com.agentframework.core.DefaultExecutionContext;
import com.agentframework.core.ExecutionContext;
import com.agentframework.core.ExecutionResult;
import com.agentframework.core.Goal;
import com.agentframework.core.RunState;
import com.agentframework.core.StateMachineRunner;
import com.agentframework.core.Task;
import com.agentframework.foundation.Origin;
import com.agentframework.foundation.TaintLabel;
import com.agentframework.foundation.WorkingMemoryEntry;
import com.agentframework.foundation.WorkingMemoryTier;
import com.agentframework.core.JobToken;
import com.agentframework.core.PlanValidator;
import com.agentframework.observability.AgentEvent;
import com.agentframework.observability.EventSink;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 *       the context is check-pointed and the snapshot is stored in
 *       {@link ExecutionStore} keyed by a new {@code tokenId}.</li>
 *   <li>The operator calls {@link #resume(JobToken, ApprovalDecision, Agent)},
 *       which loads and SHA-256-verifies the snapshot, restores the context,
 *       injects the approval decision into working memory, and re-runs.</li>
 *   <li>The original {@link CompletableFuture} is completed when the resumed
 *       run finishes.</li>
 * </ol>
 *
 * <h3>Contract for {@link ExecutionStore}</h3>
 * {@code ExecutionStore.load(id)} returns the raw {@link ExecutionContext.Snapshot}
 * or {@code null} if no snapshot exists for the given id.  It does NOT return
 * {@code Optional} — callers are responsible for null-guards.
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

    // ── Public API ────────────────────────────────────────────────────────

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
     * @param agent    the agent implementation used in {@link #suspend}
     */
    public CompletableFuture<ExecutionResult> resume(
            JobToken token, ApprovalDecision decision, Agent agent) {

        // 1. Load snapshot — ExecutionStore.load() returns nullable Snapshot,
        //    not Optional.  Guard explicitly.
        ExecutionContext.Snapshot snap = store.load(token.jobId());
        if (snap == null) {
            throw new IllegalStateException(
                "No snapshot found for jobToken=" + token.jobId());
        }

        // 2. Verify integrity before restoring any state
        verifyIntegrity(snap);

        // 3. Restore context
        Task replayTask = buildReplayTask(snap);
        DefaultExecutionContext ctx = new DefaultExecutionContext(
            replayTask, snap.runId(), "hitl-resume-user");
        ctx.restoreFromSnapshot(snap);

        // 4. Inject operator decision as a CLEAN SYSTEM working-memory entry
        String decisionText = switch (decision) {
            case ApprovalDecision.Approved  a -> "HITL_DECISION: APPROVED";
            case ApprovalDecision.Rejected  r -> "HITL_DECISION: REJECTED | " + r.reason();
            case ApprovalDecision.Modified  m -> "HITL_DECISION: MODIFIED | tool=" + m.updatedCall().toolName();
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

        // 5. Re-enter from VALIDATING so the state machine re-validates the plan
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
     * ExecutionStore.load() returns null when no snapshot exists.
     */
    public JobStatus query(JobToken token) {
        if (store.load(token.jobId()) == null) return JobStatus.NOT_FOUND;
        CompletableFuture<ExecutionResult> f = pendingFutures.get(token.jobId());
        if (f == null)  return JobStatus.NOT_FOUND;
        if (f.isDone()) return JobStatus.COMPLETED;
        return JobStatus.PENDING;
    }

    public enum JobStatus { PENDING, COMPLETED, NOT_FOUND }

    // ── Internal helpers ──────────────────────────────────────────────────

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

            // StateMachineRunner is in com.agentframework.core — import added above
            new StateMachineRunner(validator, events).run(agent, ctx);

            if (ctx.currentState() == RunState.SUSPENDED_HITL) {
                // Checkpoint and persist under a fresh tokenId.
                // ExecutionStore.save(snapshot) is 1-arg; the snapshot carries
                // its own runId as the lookup key.
                ExecutionContext.Snapshot snap = ctx.checkpoint();

                // We want the token to have its own id (distinct from the runId)
                // so that HITL tokens are not confused with run ids.  We save a
                // wrapped snapshot whose runId() is the tokenId; the store uses
                // runId() as the primary key (see InMemoryExecutionStore).
                String tokenId = UUID.randomUUID().toString();
                ExecutionContext.Snapshot tokenSnap = wrapWithId(snap, tokenId);
                store.save(tokenSnap);

                // JobToken is a 3-field record: jobId, statusEndpoint, estimatedDuration
                JobToken token = new JobToken(
                    tokenId,
                    "/api/v1/jobs/" + tokenId + "/status",
                    Duration.ofMinutes(30));
                ctx.activeJobs().put(tokenId, token);
                pendingFutures.put(ctx.runId(), future);

                emit(ctx, AgentEvent.EventType.HITL_REQUESTED,
                    Map.of("jobToken", tokenId));
                // Future remains pending until resume() completes it.

            } else {
                emit(ctx, AgentEvent.EventType.RUN_COMPLETED,
                    Map.of("state", ctx.currentState().name()));
                future.complete(ExecutionResult.from(ctx));
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    // ── Snapshot delegation helper ─────────────────────────────────────────
    //
    // Replaces the anonymous-class hack that caused List-not-found errors.
    // A named static inner class is explicit, compiler-visible, and fully typed.

    private static ExecutionContext.Snapshot wrapWithId(
            ExecutionContext.Snapshot delegate, String newRunId) {
        return new DelegatingSnapshot(delegate, newRunId);
    }

    private static final class DelegatingSnapshot implements ExecutionContext.Snapshot {
        private final ExecutionContext.Snapshot d;
        private final String overrideRunId;

        DelegatingSnapshot(ExecutionContext.Snapshot d, String overrideRunId) {
            this.d = d;
            this.overrideRunId = overrideRunId;
        }

        @Override public String                    runId()                  { return overrideRunId; }
        @Override public RunState                  state()                  { return d.state(); }
        @Override public int                       cycle()                  { return d.cycle(); }
        @Override public String                    schemaVersion()          { return d.schemaVersion(); }
        @Override public List<Goal>                goalStackSnapshot()      { return d.goalStackSnapshot(); }
        @Override public List<com.agentframework.core.WorkingMemoryEntry> workingMemorySnapshot() { return d.workingMemorySnapshot(); }
        @Override public List<com.agentframework.core.Belief>             beliefSnapshot()         { return d.beliefSnapshot(); }
        @Override public int                       totalTokens()            { return d.totalTokens(); }
        @Override public BigDecimal                totalCost()              { return d.totalCost(); }
        @Override public int                       consecutiveFailures()    { return d.consecutiveFailures(); }
        @Override public int                       stagnantCycles()         { return d.stagnantCycles(); }
        @Override public int                       stuckCycles()            { return d.stuckCycles(); }
        @Override public int                       revisionCount()          { return d.revisionCount(); }
        @Override public String                    integrityHash()          { return d.integrityHash(); }
    }

    // ── Integrity + replay helpers ─────────────────────────────────────────

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
