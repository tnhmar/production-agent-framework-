package com.agentframework.core;
import com.agentframework.foundation.*;
import com.agentframework.observability.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class AgentRuntime {
    private final PlanValidator  validator;
    private final EventSink      events;
    private static final ExecutorService ASYNC_POOL =
        Executors.newCachedThreadPool(r -> { Thread t=new Thread(r,"agent-async"); t.setDaemon(true); return t;});

    public AgentRuntime(PlanValidator validator) { this(validator, EventSink.noop()); }
    public AgentRuntime(PlanValidator validator, EventSink events) {
        this.validator=validator; this.events=events;
    }

    /** Synchronous execution — blocks until terminal state. */
    public ExecutionResult execute(Agent agent, Task task) {
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "default", "user");
        emit(ctx, AgentEvent.EventType.RUN_STARTED, Map.of("instruction", task.instruction()));
        new StateMachineRunner(validator, events).run(agent, ctx);
        emit(ctx, AgentEvent.EventType.RUN_COMPLETED, Map.of("state", ctx.currentState().name()));
        return ExecutionResult.from(ctx);
    }

    /** Async execution — returns immediately. */
    public CompletableFuture<ExecutionResult> executeAsync(Agent agent, Task task) {
        return CompletableFuture.supplyAsync(() -> execute(agent, task), ASYNC_POOL);
    }

    /** Execute with explicit tenant/user context. */
    public ExecutionResult execute(Agent agent, Task task, String tenantId, String userId) {
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, tenantId, userId);
        emit(ctx, AgentEvent.EventType.RUN_STARTED, Map.of("instruction", task.instruction()));
        new StateMachineRunner(validator, events).run(agent, ctx);
        emit(ctx, AgentEvent.EventType.RUN_COMPLETED, Map.of("state", ctx.currentState().name()));
        return ExecutionResult.from(ctx);
    }

    private void emit(ExecutionContext ctx, AgentEvent.EventType type, Map<String,Object> attrs) {
        events.emit(new AgentEvent(ctx.runId(), ctx.tenantId(), type, Instant.now(), attrs));
    }
}
