package com.agentframework.hitl;

import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.observability.*;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous agent runtime that supports Human-in-the-Loop (HITL) pause
 * and resume via a durable {@link ExecutionStore}.
 *
 * <h3>HITL flow</h3>
 * <ol>
 *   <li>{@link #suspend} starts the agent on the async pool.  If the run
 *       terminates naturally (goal complete, limits hit) the result is
 *       delivered to the caller's {@link CompletableFuture} immediately.</li>
 *   <li>If the {@link StateMachineRunner} exits with state
 *       {@link RunState#SUSPENDED_HITL}, the context is
 *       {@link ExecutionContext#checkpoint() checkpointed} and stored in
 *       {@link ExecutionStore} keyed by a fresh {@link JobToken}.  The
 *       {@link JobToken} is written into the context's {@code activeJobs}
 *       map so the agent's HITL middleware can surface it to the approver.</li>
 *   <li>The human operator calls {@link #resume(JobToken, ApprovalDecision, Agent)}
 *       which restores the snapshot, injects the approval decision as a
 *       CLEAN working-memory entry, and re-runs the state machine.</li>
 *   <li>The caller's original {@link CompletableFuture} is completed when
 *       the resumed run finishes.</li>
 * </ol>
 *
 * <p>All state is thread-safe: the pending-future map uses
 * {@link ConcurrentHashMap} and the {@link ExecutionStore} contract requires
 * atomic put/get semantics.
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

    /**
     * Full constructor — callers supply a bounded, per-tenant executor to prevent
     * one tenant's blocked HITL runs from exhausting the shared thread pool.
     */
    public AsyncAgentRuntime(PlanValidator validator, EventSink events,
                              ExecutionStore store, ExecutorService pool) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.events    = Objects.requireNonNull(events,    "events");
        this.store     = Objects.requireNonNull(store,     "store");
        this.pool      = Objects.requireNonNull(pool,      "pool");
    }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Starts an agent run asynchronously.
     *
     * <p>If the run terminates naturally the returned future is completed with
     * the {@link ExecutionResult}.  If the run is suspended for HITL, the future
     * remains pending until {@link #resume} is called.
     *
     * @return a {@link CompletableFuture} that completes when the run finishes
     *         (including after HITL resume cycles)
     */
    public CompletableFuture<ExecutionResult> suspend(
            Agent agent, Task task, String tenantId, String userId) {

        CompletableFuture<ExecutionResult> future = new CompletableFuture<>();
        CompletableFuture.runAsync(
            () -> runWithHitlSupport(agent, task, tenantId, userId, future),
            pool);
        return future;
    }

    /**
     * Resumes a suspended run.
     *
     * <p>Steps:
     * <ol>
     *   <li>Loads and integrity-verifies the snapshot from {@link ExecutionStore}.</li>
     *   <li>Restores a fresh {@link DefaultExecutionContext}.</li>
     *   <li>Injects the approval decision as a CLEAN-tainted SYSTEM working-memory
     *       entry so the agent's reasoning engine can act on it in the next cycle.</li>
     *   <li>Submits the resumed run on the async pool.</li>
     * </ol>
     *
     * @param token    the token returned when the run was first suspended
     * @param decision the human operator's approval or rejection decision
     * @param agent    the agent implementation (same one used in {@link #suspend})
     * @return a future that completes when the resumed run finishes
     * @throws IllegalStateException    if no snapshot exists for the token
     * @throws IllegalArgumentException if snapshot integrity verification fails
     */
    public CompletableFuture<ExecutionResult> resume(
            JobToken token, ApprovalDecision decision, Agent agent) {

        ExecutionContext.Snapshot snap = store.load(token.id())
            .orElseThrow(() -> new IllegalStateException(
                "No snapshot found for jobToken=" + token.id()));

        verifyIntegrity(snap);

        Task replayTask = buildReplayTask(snap);
        DefaultExecutionContext ctx = new DefaultExecutionContext(
            replayTask, snap.runId(), "hitl-resume-user");
        ctx.restoreFromSnapshot(snap);

        // Inject the approval outcome into working memory as a CLEAN system entry
        ctx.workingMemory().add(new WorkingMemoryEntry(
            UUID.randomUUID().toString(),
            "HITL_DECISION: " + decision.type().name()
                + (decision.comment() != null ? " | " + decision.comment() : ""),
            WorkingMemoryTier.ACTIVE,
            Origin.SYSTEM,
            1.0,
            Instant.now(),
            TaintLabel.CLEAN));

        // Transition back to VALIDATING so the state machine resumes from the right phase
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
     * Returns the current status of a suspended job without blocking.
     *
     * @return {@link JobStatus#PENDING} if awaiting resume,
     *         {@link JobStatus#COMPLETED} if finished,
     *         {@link JobStatus#NOT_FOUND} if the token is unknown
     */
    public JobStatus query(JobToken token) {
        if (store.load(token.id()).isEmpty()) return JobStatus.NOT_FOUND;
        CompletableFuture<ExecutionResult> f = pendingFutures.get(token.id());
        if (f == null)        return JobStatus.NOT_FOUND;
        if (f.isDone())       return JobStatus.COMPLETED;
        return JobStatus.PENDING;
    }

    public enum JobStatus { PENDING, COMPLETED, NOT_FOUND }

    // ── Internal execution helpers ──────────────────────────────────────

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
                // Checkpoint and park — the future stays pending until resume()
                ExecutionContext.Snapshot snap = ctx.checkpoint();
                String tokenId = UUID.randomUUID().toString();
                store.save(tokenId, snap);
                JobToken token = new JobToken(tokenId);
                ctx.activeJobs().put(tokenId, token);
                pendingFutures.put(ctx.runId(), future);
                emit(ctx, AgentEvent.EventType.HITL_REQUESTED,
                    Map.of("jobToken", tokenId));
                // Do NOT complete the future here — it completes in resume()
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
