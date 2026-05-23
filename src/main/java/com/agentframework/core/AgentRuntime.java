package com.agentframework.core;

import com.agentframework.foundation.*;
import com.agentframework.observability.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Synchronous and asynchronous agent execution entry point.
 *
 * <p><b>P5 fix</b>: the async thread pool is now <em>instance-scoped</em> rather
 * than a static field.  Callers can inject a per-tenant
 * {@link ExecutorService} to prevent one tenant's workload from starving another's.
 *
 * <p><b>N5 fix</b>: {@link #replay} restores a full execution context from a
 * persisted {@link ExecutionContext.Snapshot}, verifies the SHA-256 integrity hash,
 * and resumes the state machine from the checkpoint.  Use cases: post-incident
 * diagnosis, dry-run validation, regression testing against production snapshots.
 *
 * <p><b>HITL integration</b>: {@link #executeWith(Agent, DefaultExecutionContext)}
 * drives the state machine into a caller-supplied {@link DefaultExecutionContext}.
 * This allows {@code AsyncAgentRuntime} (in package {@code com.agentframework.hitl})
 * to retain a reference to the live context after the run, enabling it to call
 * {@link DefaultExecutionContext#checkpoint()} to detect and persist HITL suspension.
 */
public class AgentRuntime {

    public static final String SYSTEM_TENANT = "system";

    private final PlanValidator   validator;
    private final EventSink       events;
    private final ExecutorService asyncPool;

    public AgentRuntime(PlanValidator validator) {
        this(validator, EventSink.noop());
    }

    public AgentRuntime(PlanValidator validator, EventSink events) {
        this(validator, events, Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-async");
            t.setDaemon(true);
            return t;
        }));
    }

    /**
     * Primary constructor — accepts a caller-supplied executor for tenant isolation.
     * In multi-tenant deployments pass a per-tenant bounded pool to prevent one
     * tenant from exhausting threads that belong to another.
     */
    public AgentRuntime(PlanValidator validator, EventSink events, ExecutorService asyncPool) {
        this.validator = validator;
        this.events    = events;
        this.asyncPool = asyncPool;
    }

    /** Synchronous execution using the {@value #SYSTEM_TENANT} tenant. */
    public ExecutionResult execute(Agent agent, Task task) {
        return execute(agent, task, SYSTEM_TENANT, "user");
    }

    /** Synchronous execution with explicit tenant and user context. */
    public ExecutionResult execute(Agent agent, Task task, String tenantId, String userId) {
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, tenantId, userId);
        return executeWith(agent, ctx);
    }

    /**
     * Drives the state machine into a <em>caller-supplied</em>
     * {@link DefaultExecutionContext}.
     *
     * <p>This overload exists so that {@code AsyncAgentRuntime} can build and
     * hold the context in its own scope, then call
     * {@link DefaultExecutionContext#checkpoint()} after execution to detect
     * HITL suspension and persist the post-run snapshot.  Without this method,
     * {@code AsyncAgentRuntime} would have no access to the live context because
     * {@link ExecutionResult} carries only the final state fields, not the full
     * mutable context.
     *
     * @param agent the agent to execute
     * @param ctx   a fully initialised (or restored) execution context;
     *              its state is mutated in place by the state machine
     * @return the {@link ExecutionResult} produced when the state machine exits
     */
    public ExecutionResult executeWith(Agent agent, DefaultExecutionContext ctx) {
        emit(ctx, AgentEvent.EventType.RUN_STARTED,
            Map.of("instruction", ctx.task().instruction()));
        new StateMachineRunner(validator, events).run(agent, ctx);
        emit(ctx, AgentEvent.EventType.RUN_COMPLETED,
            Map.of("state", ctx.currentState().name()));
        return ExecutionResult.from(ctx);
    }

    /** Async execution — result delivered via the returned future. */
    public CompletableFuture<ExecutionResult> executeAsync(Agent agent, Task task) {
        return CompletableFuture.supplyAsync(() -> execute(agent, task), asyncPool);
    }

    /** Async execution with explicit tenant context. */
    public CompletableFuture<ExecutionResult> executeAsync(Agent agent, Task task,
                                                            String tenantId, String userId) {
        return CompletableFuture.supplyAsync(
            () -> execute(agent, task, tenantId, userId), asyncPool);
    }

    // ── N5: Deterministic replay ──────────────────────────────────────────

    /**
     * Resumes execution from a previously persisted snapshot.
     *
     * @param snapshot  a verified snapshot from {@link ExecutionContext#checkpoint()}
     * @param agent     agent implementation used for the resumed run
     * @param tenantId  tenant context for the resumed run
     * @param userId    user context for the resumed run
     * @return the {@link ExecutionResult} produced by the resumed run
     * @throws IllegalArgumentException if the integrity hash does not match
     */
    public ExecutionResult replay(ExecutionContext.Snapshot snapshot,
                                   Agent agent, String tenantId, String userId) {
        verifyIntegrity(snapshot);
        Task replayTask = buildReplayTask(snapshot);
        DefaultExecutionContext ctx = new DefaultExecutionContext(replayTask, tenantId, userId);
        ctx.restoreFromSnapshot(snapshot);
        emit(ctx, AgentEvent.EventType.RUN_STARTED,
            Map.of("instruction", replayTask.instruction(), "replayFrom", snapshot.cycle()));
        new StateMachineRunner(validator, events).run(agent, ctx);
        emit(ctx, AgentEvent.EventType.RUN_COMPLETED, Map.of("state", ctx.currentState().name()));
        return ExecutionResult.from(ctx);
    }

    /**
     * Overload allowing callers to supply a custom Task (e.g. with tighter limits or
     * a corrected instruction) while still replaying from the persisted memory state.
     */
    public ExecutionResult replay(ExecutionContext.Snapshot snapshot,
                                   Agent agent, Task replayTask,
                                   String tenantId, String userId) {
        verifyIntegrity(snapshot);
        DefaultExecutionContext ctx = new DefaultExecutionContext(replayTask, tenantId, userId);
        ctx.restoreFromSnapshot(snapshot);
        emit(ctx, AgentEvent.EventType.RUN_STARTED,
            Map.of("instruction", replayTask.instruction(), "replayFrom", snapshot.cycle()));
        new StateMachineRunner(validator, events).run(agent, ctx);
        emit(ctx, AgentEvent.EventType.RUN_COMPLETED, Map.of("state", ctx.currentState().name()));
        return ExecutionResult.from(ctx);
    }

    /** Convenience overload using SYSTEM_TENANT for tooling / testing. */
    public ExecutionResult replay(ExecutionContext.Snapshot snapshot, Agent agent) {
        return replay(snapshot, agent, SYSTEM_TENANT, "replay-user");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void verifyIntegrity(ExecutionContext.Snapshot snapshot) {
        String expected = DefaultExecutionContext.computeSnapshotHash(snapshot);
        if (!expected.equals(snapshot.integrityHash())) {
            throw new IllegalArgumentException(
                "Snapshot integrity check failed for runId=" + snapshot.runId() +
                " at cycle=" + snapshot.cycle() +
                ". Expected=" + expected + ", stored=" + snapshot.integrityHash() +
                ". Snapshot may be corrupted or tampered.");
        }
    }

    private Task buildReplayTask(ExecutionContext.Snapshot snapshot) {
        String instruction = snapshot.goalStackSnapshot().stream()
            .filter(g -> "root".equals(g.id()))
            .findFirst()
            .map(Goal::description)
            .orElse("(replay — instruction unavailable)");
        return Task.builder()
            .instruction(instruction)
            .maxCycles(Integer.MAX_VALUE / 2)
            .maxTokens(Integer.MAX_VALUE / 2)
            .build();
    }

    private void emit(ExecutionContext ctx, AgentEvent.EventType type, Map<String, Object> attrs) {
        events.emit(new AgentEvent(ctx.runId(), ctx.tenantId(), type, Instant.now(), attrs));
    }
}
