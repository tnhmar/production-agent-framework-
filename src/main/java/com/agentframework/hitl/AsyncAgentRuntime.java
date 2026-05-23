package com.agentframework.hitl;

import com.agentframework.core.Agent;
import com.agentframework.core.AgentRuntime;
import com.agentframework.core.Belief;
import com.agentframework.core.DefaultExecutionContext;
import com.agentframework.core.ExecutionContext;
import com.agentframework.core.ExecutionResult;
import com.agentframework.core.Goal;
import com.agentframework.core.JobToken;
import com.agentframework.core.PlanValidator;
import com.agentframework.core.WorkingMemory;
import com.agentframework.core.WorkingMemoryEntry;
import com.agentframework.core.WorkingMemoryTier;
import com.agentframework.foundation.Origin;
import com.agentframework.foundation.RunState;
import com.agentframework.foundation.TaintLabel;
import com.agentframework.foundation.Task;
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
 * <h3>Design constraints</h3>
 * <ul>
 *   <li>{@link com.agentframework.core.StateMachineRunner} is intentionally
 *       package-private; this class must not reference it directly.</li>
 *   <li>Execution is delegated to the public {@link AgentRuntime} facade.
 *       For fresh runs {@link AgentRuntime#execute} is used on a dedicated
 *       async pool; for resume {@link AgentRuntime#replay} is used.</li>
 * </ul>
 *
 * <h3>HITL flow</h3>
 * <ol>
 *   <li>{@link #suspend} submits the agent to the async pool and returns a
 *       {@link CompletableFuture} immediately.</li>
 *   <li>When the synchronous runtime exits with {@link RunState#SUSPENDED_HITL},
 *       the context is check-pointed, the snapshot is stored in
 *       {@link ExecutionStore} keyed by a fresh {@code tokenId}, and a
 *       {@link JobToken} is placed in the run's {@code activeJobs()} map.
 *       The {@link CompletableFuture} remains pending.</li>
 *   <li>The operator calls {@link #resume(JobToken, ApprovalDecision, Agent)},
 *       which loads the snapshot, verifies its SHA-256 hash, injects the
 *       decision into working memory, and calls {@link AgentRuntime#replay}.</li>
 *   <li>The original {@link CompletableFuture} is completed when the resumed
 *       run finishes.</li>
 * </ol>
 */
public class AsyncAgentRuntime {

    private final AgentRuntime    runtime;
    private final EventSink       events;
    private final ExecutionStore  store;
    private final ExecutorService pool;

    /** Pending futures keyed by the original runId. */
    private final ConcurrentHashMap<String, CompletableFuture<ExecutionResult>>
        pendingFutures = new ConcurrentHashMap<>();

    // ── Constructors ────────────────────────────────────────────────────────

    public AsyncAgentRuntime(PlanValidator validator, EventSink events, ExecutionStore store) {
        this(new AgentRuntime(validator, events), events, store, defaultPool());
    }

    public AsyncAgentRuntime(AgentRuntime runtime, EventSink events,
                              ExecutionStore store, ExecutorService pool) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.events  = Objects.requireNonNull(events,  "events");
        this.store   = Objects.requireNonNull(store,   "store");
        this.pool    = Objects.requireNonNull(pool,    "pool");
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Starts a new agent run asynchronously.
     *
     * @return a future that completes when the run finishes or is suspended
     */
    public CompletableFuture<ExecutionResult> suspend(
            Agent agent, Task task, String tenantId, String userId) {

        CompletableFuture<ExecutionResult> future = new CompletableFuture<>();
        CompletableFuture.runAsync(
            () -> runAndHandleSuspension(agent, task, tenantId, userId, null, future),
            pool);
        return future;
    }

    /**
     * Resumes a previously suspended HITL run.
     *
     * <p>{@link ExecutionStore#load(String)} returns a raw (nullable)
     * {@link ExecutionContext.Snapshot} — it does <em>not</em> return
     * {@code Optional}.</p>
     *
     * @param token    the token issued when the run was suspended
     * @param decision the operator's approval / rejection / modification
     * @param agent    the same agent implementation used in {@link #suspend}
     * @return the future that was originally returned by {@link #suspend}
     */
    public CompletableFuture<ExecutionResult> resume(
            JobToken token, ApprovalDecision decision, Agent agent) {

        // load() is nullable — no Optional, guard explicitly
        ExecutionContext.Snapshot snap = store.load(token.jobId());
        if (snap == null) {
            throw new IllegalStateException(
                "No HITL snapshot found for jobToken=" + token.jobId());
        }

        // Inject operator decision as a CLEAN SYSTEM working-memory entry.
        // We create a fresh context from snapshot, add the entry, checkpoint
        // again, then hand the enriched snapshot to AgentRuntime.replay().
        DefaultExecutionContext ctx = new DefaultExecutionContext(
            buildReplayTask(snap), snap.runId(), "hitl-resume");
        ctx.restoreFromSnapshot(snap);
        ctx.workingMemory().add(buildDecisionEntry(decision));
        ctx.transitionTo(RunState.VALIDATING);

        CompletableFuture<ExecutionResult> future =
            pendingFutures.computeIfAbsent(snap.runId(), id -> new CompletableFuture<>());

        ExecutionContext.Snapshot enriched = ctx.checkpoint();

        CompletableFuture.runAsync(() -> {
            try {
                // AgentRuntime.replay() is the public cross-package entry point;
                // it verifies integrity, restores state, and runs StateMachineRunner.
                ExecutionResult result = runtime.replay(
                    enriched, agent, snap.runId(), "hitl-resume");
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, pool);

        return future;
    }

    /**
     * Non-blocking status poll.
     * Returns {@link JobStatus#NOT_FOUND} when no snapshot exists
     * (store.load returns null).
     */
    public JobStatus query(JobToken token) {
        if (store.load(token.jobId()) == null) return JobStatus.NOT_FOUND;
        CompletableFuture<ExecutionResult> f = pendingFutures.get(token.jobId());
        if (f == null)  return JobStatus.NOT_FOUND;
        if (f.isDone()) return JobStatus.COMPLETED;
        return JobStatus.PENDING;
    }

    public enum JobStatus { PENDING, COMPLETED, NOT_FOUND }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void runAndHandleSuspension(
            Agent agent, Task task, String tenantId, String userId,
            String existingRunId, CompletableFuture<ExecutionResult> future) {
        try {
            emit(tenantId, existingRunId != null ? existingRunId : "pending",
                AgentEvent.EventType.RUN_STARTED,
                Map.of("instruction", task.instruction()));

            // Delegate to the public runtime facade — it owns StateMachineRunner.
            ExecutionResult result = runtime.execute(agent, task, tenantId, userId);

            if (result.finalState() == RunState.SUSPENDED_HITL) {
                handleSuspension(result, future);
            } else {
                future.complete(result);
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    private void handleSuspension(
            ExecutionResult result, CompletableFuture<ExecutionResult> future) {

        ExecutionContext.Snapshot snap = result.snapshot();
        String tokenId = UUID.randomUUID().toString();

        // Wrap with a tokenId-keyed snapshot so the store uses tokenId as key.
        ExecutionContext.Snapshot tokenSnap = new DelegatingSnapshot(snap, tokenId);
        store.save(tokenSnap);

        JobToken token = new JobToken(
            tokenId,
            "/api/v1/jobs/" + tokenId + "/status",
            Duration.ofMinutes(30));

        pendingFutures.put(snap.runId(), future);

        emit(snap.runId(), snap.runId(),
            AgentEvent.EventType.HITL_REQUESTED,
            Map.of("jobToken", tokenId));
        // Future remains pending until resume() completes it.
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WorkingMemoryEntry buildDecisionEntry(ApprovalDecision decision) {
        String text = switch (decision) {
            case ApprovalDecision.Approved  a -> "HITL_DECISION: APPROVED";
            case ApprovalDecision.Rejected  r -> "HITL_DECISION: REJECTED | " + r.reason();
            case ApprovalDecision.Modified  m -> "HITL_DECISION: MODIFIED | tool=" + m.updatedCall().toolName();
            case ApprovalDecision.Escalated e -> "HITL_DECISION: ESCALATED | " + e.reason();
        };
        return new WorkingMemoryEntry(
            UUID.randomUUID().toString(),
            text,
            WorkingMemoryTier.ACTIVE,
            Origin.SYSTEM,
            1.0,
            Instant.now(),
            TaintLabel.CLEAN);
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

    private void emit(String tenantId, String runId,
                      AgentEvent.EventType type, Map<String, Object> attrs) {
        events.emit(new AgentEvent(runId, tenantId, type, Instant.now(), attrs));
    }

    private static ExecutorService defaultPool() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-hitl-async");
            t.setDaemon(true);
            return t;
        });
    }

    // ── DelegatingSnapshot ────────────────────────────────────────────────────
    //
    // Named static inner class — fully participates in the file's import scope.
    // Replaces the anonymous class that caused "cannot find symbol: class List"
    // errors because anonymous classes do not inherit enclosing import declarations.

    private static final class DelegatingSnapshot implements ExecutionContext.Snapshot {

        private final ExecutionContext.Snapshot d;
        private final String                    overrideRunId;

        DelegatingSnapshot(ExecutionContext.Snapshot d, String overrideRunId) {
            this.d             = Objects.requireNonNull(d);
            this.overrideRunId = Objects.requireNonNull(overrideRunId);
        }

        @Override public String             runId()                  { return overrideRunId; }
        @Override public RunState           state()                  { return d.state(); }
        @Override public int                cycle()                  { return d.cycle(); }
        @Override public String             schemaVersion()          { return d.schemaVersion(); }
        @Override public List<Goal>         goalStackSnapshot()      { return d.goalStackSnapshot(); }
        @Override public List<WorkingMemoryEntry> workingMemorySnapshot() { return d.workingMemorySnapshot(); }
        @Override public List<Belief>       beliefSnapshot()         { return d.beliefSnapshot(); }
        @Override public int                totalTokens()            { return d.totalTokens(); }
        @Override public BigDecimal         totalCost()              { return d.totalCost(); }
        @Override public int                consecutiveFailures()    { return d.consecutiveFailures(); }
        @Override public int                stagnantCycles()         { return d.stagnantCycles(); }
        @Override public int                stuckCycles()            { return d.stuckCycles(); }
        @Override public int                revisionCount()          { return d.revisionCount(); }
        @Override public String             integrityHash()          { return d.integrityHash(); }
    }
}
