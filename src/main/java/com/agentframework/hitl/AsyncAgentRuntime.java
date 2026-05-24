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
 * All execution is delegated to {@link AgentRuntime#executeWith}.
 *
 * <h3>AAR-2 fix</h3>
 * {@link #runFromStoredSnapshot} previously constructed the
 * {@link DefaultExecutionContext} with {@code snap.runId()} as the
 * {@code tenantId} parameter — a copy-paste error that caused all
 * tenant-policy lookups and observability events during HITL resume to use
 * the run UUID as a tenant identifier. The tenant ID is now derived from
 * the snapshot's goal-stack description prefix (stored as
 * {@code "tenantId:<value>"} by convention), falling back to
 * {@link AgentRuntime#SYSTEM_TENANT}.
 */
public class AsyncAgentRuntime {

    private final AgentRuntime    runtime;
    private final EventSink       events;
    private final ExecutionStore  store;
    private final ExecutorService pool;

    private final ConcurrentHashMap<String, CompletableFuture<ExecutionResult>>
        pendingFutures = new ConcurrentHashMap<>();

    // ── Constructors ────────────────────────────────────────────────────────

    public AsyncAgentRuntime(PlanValidator validator, EventSink events,
                              ExecutionStore store) {
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

    public CompletableFuture<ExecutionResult> suspend(
            Agent agent, Task task, String tenantId, String userId) {
        CompletableFuture<ExecutionResult> future = new CompletableFuture<>();
        CompletableFuture.runAsync(
            () -> runWithContextCapture(agent, task, tenantId, userId, null, future),
            pool);
        return future;
    }

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
     * Returns the current status of an asynchronous job.
     *
     * <p>{@link InMemoryExecutionStore#load} throws
     * {@link IllegalArgumentException} when the runId is absent rather than
     * returning {@code null}. We treat that exception as {@link JobStatus#NOT_FOUND},
     * which is the correct semantic: the job simply does not exist in this runtime.
     */
    public JobStatus query(JobToken token) {
        try {
            store.load(token.jobId());
        } catch (IllegalArgumentException e) {
            return JobStatus.NOT_FOUND;
        }
        CompletableFuture<ExecutionResult> f = pendingFutures.get(token.jobId());
        if (f == null)  return JobStatus.NOT_FOUND;
        if (f.isDone()) return JobStatus.COMPLETED;
        return JobStatus.PENDING;
    }

    public enum JobStatus { PENDING, COMPLETED, NOT_FOUND }

    // ── Execution paths ──────────────────────────────────────────────────

    /**
     * Core execution path: builds and owns a {@link DefaultExecutionContext},
     * drives it via {@link AgentRuntime#executeWith}, then checkpoints on
     * HITL suspension.
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
     * Resume path: restore snapshot, inject operator decision, then execute.
     *
     * <p><b>AAR-2 fix:</b> the tenant ID is now derived from the snapshot
     * itself (stored in the root goal's {@code successCriteria} field as a
     * {@code "tenantId:<value>"} prefix), not from {@code snap.runId()} as
     * was incorrectly done before.
     */
    private void runFromStoredSnapshot(
            ExecutionContext.Snapshot snap, ApprovalDecision decision,
            Agent agent, CompletableFuture<ExecutionResult> future) {
        try {
            Task replayTask = buildReplayTask(snap);

            // AAR-2 fix: extract the real tenantId from the snapshot instead
            // of incorrectly passing snap.runId() as the tenantId.
            String tenantId = extractTenantId(snap);

            DefaultExecutionContext ctx =
                new DefaultExecutionContext(replayTask, tenantId, "hitl-resume");
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
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * AAR-2 fix: extracts the tenant ID from the root goal's successCriteria
     * field, which by convention is prefixed with {@code "tenantId:<value>;"}.
     * Falls back to {@link AgentRuntime#SYSTEM_TENANT} when not present.
     *
     * <p>Example stored criteria: {@code "tenantId:acme-corp;achieve X"}
     */
    private String extractTenantId(ExecutionContext.Snapshot snap) {
        return snap.goalStackSnapshot().stream()
            .filter(g -> "root".equals(g.id()))
            .findFirst()
            .map(g -> {
                String criteria = g.successCriteria();
                if (criteria != null && criteria.startsWith("tenantId:")) {
                    int end = criteria.indexOf(';');
                    return end > 0
                        ? criteria.substring(9, end)
                        : criteria.substring(9);
                }
                return AgentRuntime.SYSTEM_TENANT;
            })
            .orElse(AgentRuntime.SYSTEM_TENANT);
    }

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
