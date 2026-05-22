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
 * <p>IC5 fix: all execution paths require an explicit tenantId.
 * The convenience {@link #execute(Agent, Task)} overload uses the
 * well-known tenant {@code "system"} which callers should register
 * a suitable {@link com.agentframework.security.TenantPolicy} for.
 */
public class AgentRuntime {
    /** Well-known tenant used by the no-arg convenience overload. Register a policy for it. */
    public static final String SYSTEM_TENANT = "system";

    private final PlanValidator  validator;
    private final EventSink      events;
    private static final ExecutorService ASYNC_POOL =
        Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-async");
            t.setDaemon(true);
            return t;
        });

    public AgentRuntime(PlanValidator validator) { this(validator, EventSink.noop()); }

    public AgentRuntime(PlanValidator validator, EventSink events) {
        this.validator = validator;
        this.events    = events;
    }

    /**
     * Synchronous execution using the {@value #SYSTEM_TENANT} tenant.
     * Register a TenantPolicy for "system" before calling this in production.
     */
    public ExecutionResult execute(Agent agent, Task task) {
        return execute(agent, task, SYSTEM_TENANT, "user");
    }

    /** Synchronous execution with explicit tenant and user context. */
    public ExecutionResult execute(Agent agent, Task task, String tenantId, String userId) {
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, tenantId, userId);
        emit(ctx, AgentEvent.EventType.RUN_STARTED, Map.of("instruction", task.instruction()));
        new StateMachineRunner(validator, events).run(agent, ctx);
        emit(ctx, AgentEvent.EventType.RUN_COMPLETED, Map.of("state", ctx.currentState().name()));
        return ExecutionResult.from(ctx);
    }

    /** Asynchronous execution — returns immediately; result delivered via the future. */
    public CompletableFuture<ExecutionResult> executeAsync(Agent agent, Task task) {
        return CompletableFuture.supplyAsync(() -> execute(agent, task), ASYNC_POOL);
    }

    /** Asynchronous execution with explicit tenant context. */
    public CompletableFuture<ExecutionResult> executeAsync(Agent agent, Task task,
                                                            String tenantId, String userId) {
        return CompletableFuture.supplyAsync(() -> execute(agent, task, tenantId, userId), ASYNC_POOL);
    }

    private void emit(ExecutionContext ctx, AgentEvent.EventType type, Map<String, Object> attrs) {
        events.emit(new AgentEvent(ctx.runId(), ctx.tenantId(), type, Instant.now(), attrs));
    }
}
