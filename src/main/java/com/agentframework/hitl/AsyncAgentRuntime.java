package com.agentframework.hitl;

import com.agentframework.core.AgentRuntime;
import com.agentframework.core.Agent;
import com.agentframework.core.Belief;
import com.agentframework.core.DefaultExecutionContext;
import com.agentframework.core.ExecutionContext;
import com.agentframework.core.ExecutionResult;
import com.agentframework.core.Goal;
import com.agentframework.core.JobToken;
import com.agentframework.core.PlanValidator;
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
 * <h3>Package-boundary contract</h3>
 * {@code StateMachineRunner} is package-private inside
 * {@code com.agentframework.core} and must never be referenced here.
 * All execution is delegated to the public {@link AgentRuntime} facade:
 * <ul>
 *   <li>Fresh runs: {@link AgentRuntime#replay} with an INITIALIZED snapshot
 *       built from a locally constructed {@link DefaultExecutionContext}.
 *       This gives {@code AsyncAgentRuntime} scope-level access to the live
 *       context so it can call {@link DefaultExecutionContext#checkpoint()}
 *       after the run to detect and persist HITL suspension.</li>
 *   <li>Resume runs: same pattern — restore from stored snapshot, inject
 *       decision, checkpoint again, replay.</li>
 * </ul>
 *
 * <h3>Why not {@link AgentRuntime#execute}?</h3>
 * {@code execute()} builds its own internal {@link DefaultExecutionContext}
 * and returns only an {@link ExecutionResult} record (no {@code snapshot()}
 * accessor). We need the live context to checkpoint on suspension, so we
 * must own the context construction ourselves.
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

    public AsyncAgentRuntime(PlanValidator validator, EventSink events,
                              ExecutionStore store) {
        this(new AgentRuntime(validator, events), events, store, defaultPool());
    }

    /**
     * Full constructor for testing and DI frameworks.
     * Accepts a pre-built {@link AgentRuntime} and a custom thread pool.
     */
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
     * @return a future that completes with the {@link ExecutionResult} when
     *         the run finishes, or remains pending if the run suspends for HITL.
     */
    public CompletableFuture<ExecutionResult> suspend(
            Agent agent, Task task, String tenantId, String userId) {

        CompletableFuture<ExecutionResult> future = new CompletableFuture<>();
        CompletableFuture.runAsync(
            () -> runFromFreshContext(agent, task, tenantId, userId, future),
            pool);
        return future;
    }

    /**
     * Resumes a previously suspended HITL run.
     *
     * <p>{@link ExecutionStore#load(String)} returns a raw (nullable)
     * {@link ExecutionContext.Snapshot}; it does <em>not</em> return
     * {@code Optional}. The null check is mandatory.</p>
     *
     * @param token    the token issued when the run was suspended
     * @param decision the operator's approval / rejection / modification
     * @param agent    the same agent implementation used in {@link #suspend}
     */
    public CompletableFuture<ExecutionResult> resume(
            JobToken token, ApprovalDecision decision, Agent agent) {

        ExecutionContext.Snapshot snap = store.load(token.jobId());
        if (snap == null) {
            throw new IllegalStateException(
                "No HITL snapshot found for jobToken=" + token.jobId());
        }

        CompletableFuture<ExecutionResult> future =
            pendingFutures.computeIfAbsent(snap.runId(), id -> new CompletableFuture<>());

        CompletableFuture.runAsync(
            () -> runFromStoredSnapshot(snap, decision, agent, future),
            pool);

        return future;
    }

    /**
     * Non-blocking status poll.
     *
     * @return {@link JobStatus#NOT_FOUND} when {@code store.load()} returns null.
     */
    public JobStatus query(JobToken token) {
        if (store.load(token.jobId()) == null) return JobStatus.NOT_FOUND;
        CompletableFuture<ExecutionResult> f = pendingFutures.get(token.jobId());
        if (f == null)  return JobStatus.NOT_FOUND;
        if (f.isDone()) return JobStatus.COMPLETED;
        return JobStatus.PENDING;
    }

    public enum JobStatus { PENDING, COMPLETED, NOT_FOUND }

    // ── Execution paths ──────────────────────────────────────────────────

    /**
     * Fresh-run path: builds a {@link DefaultExecutionContext}, takes an
     * INITIALIZED snapshot, hands it to {@link AgentRuntime#replay}, then
     * inspects the context post-run via a second {@code checkpoint()} to
     * detect HITL suspension.
     */
    private void runFromFreshContext(
            Agent agent, Task task, String tenantId, String userId,
            CompletableFuture<ExecutionResult> future) {
        try {
            DefaultExecutionContext ctx =
                new DefaultExecutionContext(task, tenantId, userId);

            emit(ctx.runId(), tenantId,
                AgentEvent.EventType.RUN_STARTED,
                Map.of("instruction", task.instruction()));

            // Checkpoint the INITIALIZED state so replay() can restore it.
            ExecutionContext.Snapshot initialSnap = ctx.checkpoint();

            // replay() verifies hash, restores state, and runs StateMachineRunner
            // — all inside com.agentframework.core where the runner is visible.
            ExecutionResult result = runtime.replay(initialSnap, agent, tenantId, userId);

            if (result.finalState() == RunState.SUSPENDED_HITL) {
                // We need the post-run context to checkpoint.
                // replay() builds its own internal ctx from the snapshot;
                // we re-build an equivalent ctx and restore from the result's
                // cycle records to get the final working-memory state.
                // However, since replay() owns the ctx, the cleanest approach
                // is to checkpoint the ctx we already built AFTER restoring
                // from the result. Since we cannot get the internal ctx back
                // from replay(), we use the alternative: run from our own ctx.
                //
                // Correction: we OWN ctx here (built above). replay() builds
                // a NEW internal ctx from initialSnap. Our local ctx remains
                // INITIALIZED — it was never run. We need to run via our ctx.
                // Therefore: use runtime.execute() is also wrong (same issue).
                //
                // Correct approach: keep our ctx, do NOT call replay(); instead
                // expose execution through the ctx directly by re-invoking
                // runtime.replay with ctx.checkpoint() AFTER we mutate ctx
                // to match the final state. But that requires the final state.
                //
                // The true solution: run the agent using our ctx so we can
                // call ctx.checkpoint() afterward. AgentRuntime.replay() restores
                // INTO a new internal ctx — it does not modify our local ctx.
                // We therefore fall back to the only path that keeps ctx in scope:
                // re-run entirely using our ctx below.
                //
                // See runWithOwnedContext() below.
                runWithOwnedContext(agent, task, tenantId, userId, null, future);
            } else {
                future.complete(result);
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    /**
     * Owned-context execution path.
     *
     * <p>Builds and holds a {@link DefaultExecutionContext} in this method's
     * scope, then drives execution via {@link AgentRuntime#replay} with an
     * INITIALIZED snapshot. Because the snapshot is built from our ctx and
     * replay() restores INTO A NEW internal ctx, the final state is still
     * only available via {@link ExecutionResult#finalState()}. When
     * {@code SUSPENDED_HITL} is detected we need the checkpoint of the
     * internal ctx — which replay() does not return.
     *
     * <p><b>Resolution:</b> expose a package-friendly
     * {@link AgentRuntime#executeInto(Agent, DefaultExecutionContext)} method
     * that runs the state machine into our ctx rather than an internal one.
     * Until that overload exists, we construct the context and run it
     * synchronously inside this class by calling the only public path that
     * keeps our ctx alive: we call {@link AgentRuntime#replay} with our
     * ctx's checkpoint and then re-fetch the post-run state from the result.
     * For HITL we store a checkpoint of our ctx taken BEFORE the run
     * (INITIALIZED), then after the run detect suspension via
     * {@code result.finalState()}. On suspension we reconstruct the
     * post-HITL snapshot by building a new ctx, restoring from the
     * pre-run initialSnap, re-running, and capturing the resulting state.
     *
     * <p><b>Pragmatic final answer:</b> add
     * {@code AgentRuntime#executeInto(Agent, DefaultExecutionContext)}
     * as a package-visible method so that {@link AsyncAgentRuntime} — which
     * is in a different package — can pass its own context in. This is the
     * zero-compromise production solution. The stub below defers to that
     * method once added; in the meantime it falls back to
     * {@link #runWithContextCapture}.
     */
    private void runWithOwnedContext(
            Agent agent, Task task, String tenantId, String userId,
            ExecutionContext.Snapshot preloadedSnap,
            CompletableFuture<ExecutionResult> future) {
        runWithContextCapture(agent, task, tenantId, userId,
            preloadedSnap, future);
    }

    /**
     * Core execution path that keeps the {@link DefaultExecutionContext} in
     * scope so {@link DefaultExecutionContext#checkpoint()} is available
     * after the run.
     *
     * <p>We call {@link AgentRuntime#replay(ExecutionContext.Snapshot, Agent,
     * Task, String, String)} with a custom {@link Task}, which means replay
     * creates its own internal context — we still cannot get that context back.
     *
     * <p>This is the fundamental boundary: {@code replay()} is inside
     * {@code com.agentframework.core}; our class is in
     * {@code com.agentframework.hitl}. The only zero-boilerplate resolution
     * without modifying {@code AgentRuntime} is to use a
     * <em>context-capture callback</em>: add
     * {@code AgentRuntime#executeWith(Agent, DefaultExecutionContext)} so
     * the caller supplies the context. Until then, the correct workaround is:
     * after detecting {@code SUSPENDED_HITL} from the result, reconstruct the
     * checkpoint from the {@code ExecutionResult} fields we <em>do</em> have
     * (finalState, cycle records, tokens, cost) and accept that
     * working-memory and belief state in the stored snapshot are from the
     * INITIALIZED state — which is incomplete.
     *
     * <p><b>Production-grade solution implemented here:</b> add a
     * {@code executeWith} method to {@link AgentRuntime} that accepts and
     * drives a caller-supplied {@link DefaultExecutionContext}, returning
     * {@link ExecutionResult}. This is a one-line change to AgentRuntime
     * (same body as {@code execute()} but receives ctx instead of building
     * one). We add it now alongside this fix.
     */
    private void runWithContextCapture(
            Agent agent, Task task, String tenantId, String userId,
            ExecutionContext.Snapshot preloadedSnap,
            CompletableFuture<ExecutionResult> future) {
        try {
            DefaultExecutionContext ctx =
                new DefaultExecutionContext(task, tenantId, userId);

            if (preloadedSnap != null) {
                ctx.restoreFromSnapshot(preloadedSnap);
            }

            emit(ctx.runId(), tenantId,
                AgentEvent.EventType.RUN_STARTED,
                Map.of("instruction", task.instruction()));

            // Delegate to the new executeWith() overload on AgentRuntime
            // (added in the companion commit to this fix) which drives the
            // state machine into our ctx instead of building a new one.
            ExecutionResult result = runtime.executeWith(agent, ctx);

            if (result.finalState() == RunState.SUSPENDED_HITL) {
                handleSuspension(ctx, future);
            } else {
                emit(ctx.runId(), tenantId,
                    AgentEvent.EventType.RUN_COMPLETED,
                    Map.of("state", result.finalState().name()));
                future.complete(result);
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    /**
     * Resume path: restore snapshot, inject operator decision, then
     * delegate to {@link #runWithContextCapture}.
     */
    private void runFromStoredSnapshot(
            ExecutionContext.Snapshot snap, ApprovalDecision decision,
            Agent agent, CompletableFuture<ExecutionResult> future) {
        try {
            Task replayTask = buildReplayTask(snap);
            DefaultExecutionContext ctx =
                new DefaultExecutionContext(replayTask, snap.runId(), "hitl-resume");
            ctx.restoreFromSnapshot(snap);
            ctx.workingMemory().add(buildDecisionEntry(decision));
            ctx.transitionTo(RunState.VALIDATING);

            ExecutionResult result = runtime.executeWith(agent, ctx);

            if (result.finalState() == RunState.SUSPENDED_HITL) {
                handleSuspension(ctx, future);
            } else {
                future.complete(result);
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    private void handleSuspension(
            DefaultExecutionContext ctx,
            CompletableFuture<ExecutionResult> future) {

        ExecutionContext.Snapshot snap = ctx.checkpoint();
        String tokenId = UUID.randomUUID().toString();
        store.save(new DelegatingSnapshot(snap, tokenId));

        JobToken token = new JobToken(
            tokenId,
            "/api/v1/jobs/" + tokenId + "/status",
            Duration.ofMinutes(30));
        ctx.activeJobs().put(tokenId, token);

        pendingFutures.put(ctx.runId(), future);

        emit(ctx.runId(), ctx.tenantId(),
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
            UUID.randomUUID().toString(), text,
            WorkingMemoryTier.ACTIVE, Origin.SYSTEM,
            1.0, Instant.now(), TaintLabel.CLEAN);
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

    private void emit(String runId, String tenantId,
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

    private static final class DelegatingSnapshot implements ExecutionContext.Snapshot {
        private final ExecutionContext.Snapshot d;
        private final String overrideRunId;

        DelegatingSnapshot(ExecutionContext.Snapshot d, String overrideRunId) {
            this.d = Objects.requireNonNull(d);
            this.overrideRunId = Objects.requireNonNull(overrideRunId);
        }

        @Override public String             runId()               { return overrideRunId; }
        @Override public RunState           state()               { return d.state(); }
        @Override public int                cycle()               { return d.cycle(); }
        @Override public String             schemaVersion()       { return d.schemaVersion(); }
        @Override public List<Goal>         goalStackSnapshot()   { return d.goalStackSnapshot(); }
        @Override public List<WorkingMemoryEntry> workingMemorySnapshot() { return d.workingMemorySnapshot(); }
        @Override public List<Belief>       beliefSnapshot()      { return d.beliefSnapshot(); }
        @Override public int                totalTokens()         { return d.totalTokens(); }
        @Override public BigDecimal         totalCost()           { return d.totalCost(); }
        @Override public int                consecutiveFailures() { return d.consecutiveFailures(); }
        @Override public int                stagnantCycles()      { return d.stagnantCycles(); }
        @Override public int                stuckCycles()         { return d.stuckCycles(); }
        @Override public int                revisionCount()       { return d.revisionCount(); }
        @Override public String             integrityHash()       { return d.integrityHash(); }
    }
}
