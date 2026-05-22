package com.agentframework.action;
import com.agentframework.action.middleware.*;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
public class DefaultAction implements Action {
    private final ToolRegistry        registry;
    private final List<ActionValidator> validators;
    private final ToolMiddleware      middleware;
    private final ToolDispatcher      dispatcher;
    private final ExecutorService     executor;

    public DefaultAction(ToolRegistry registry, List<ActionValidator> validators,
                         ToolMiddleware middleware, ToolDispatcher dispatcher) {
        this(registry, validators, middleware, dispatcher,
             Executors.newFixedThreadPool(4, r -> { Thread t=new Thread(r,"tool-exec"); t.setDaemon(true); return t; }));
    }
    public DefaultAction(ToolRegistry registry, List<ActionValidator> validators,
                         ToolMiddleware middleware, ToolDispatcher dispatcher, ExecutorService executor) {
        this.registry=registry; this.validators=validators;
        this.middleware=middleware; this.dispatcher=dispatcher; this.executor=executor;
    }

    public ActionResult execute(Decision decision, ExecutionContext ctx) {
        return switch (decision) {
            case ToolCall tc         -> executeToolCall(tc, ctx);
            case ParallelToolCalls p -> executeParallel(p, ctx);
            case FinalAnswer fa      -> ActionResult.success(
                new ToolResult(fa.content(), List.of(), 0, BigDecimal.ZERO, Duration.ZERO));
            case Escalate e          -> ActionResult.failure("ESCALATED", e.reason());
            case AskClarification q  -> ActionResult.failure("CLARIFY", q.question());
        };
    }

    private ActionResult executeToolCall(ToolCall tc, ExecutionContext ctx) {
        ToolContract contract = registry.lookup(tc.toolName());
        if (contract == null)
            return ActionResult.failure("UNKNOWN_TOOL", "No contract: " + tc.toolName());
        for (ActionValidator v : validators) {
            ValidationVerdict vr = v.validate(tc, contract, ctx);
            if (!vr.isPassed()) return ActionResult.validationFailure(vr);
        }
        ToolInvocation inv = new ToolInvocation(contract, tc.arguments(), ctx, ValidationVerdict.ok());
        try {
            ToolResult r = middleware.apply(inv, i -> {
                try { return dispatcher.dispatch(i); }
                catch (ToolException e) { throw new RuntimeException(e); }
            });
            ctx.addTokens(r.tokensUsed()); ctx.addCost(r.cost());
            return ActionResult.success(r);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() instanceof ToolException te ? te : e;
            String code = cause instanceof ToolException te2 ? te2.errorCode() : "TOOL_ERROR";
            return ActionResult.failure(code, cause.getMessage());
        }
    }

    private ActionResult executeParallel(ParallelToolCalls p, ExecutionContext ctx) {
        List<ToolCall> calls = p.calls();
        // validate all first
        for (ToolCall tc : calls) {
            ToolContract c = registry.lookup(tc.toolName());
            if (c == null) return ActionResult.failure("UNKNOWN_TOOL", tc.toolName());
            for (ActionValidator v : validators) {
                ValidationVerdict vr = v.validate(tc, c, ctx);
                if (!vr.isPassed()) return ActionResult.validationFailure(vr);
            }
        }
        List<Future<ToolResult>> futures = calls.stream()
            .map(tc -> executor.submit(() -> {
                ToolContract c = registry.lookup(tc.toolName());
                ToolInvocation inv = new ToolInvocation(c, tc.arguments(), ctx, ValidationVerdict.ok());
                return middleware.apply(inv, i -> {
                    try { return dispatcher.dispatch(i); }
                    catch (ToolException e) { throw new RuntimeException(e); }
                });
            })).toList();

        List<ToolResult> results = new ArrayList<>();
        List<String>     errors  = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get(p.deadline().toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                errors.add("timeout:" + calls.get(i).toolName());
            } catch (ExecutionException e) {
                errors.add(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ActionResult.failure("INTERRUPTED", e.getMessage());
            }
        }
        if (!errors.isEmpty() && p.requireAll())
            return ActionResult.failure("PARALLEL_ATOMIC_FAILURE", String.join("; ", errors));
        return ActionResult.partial(results, errors);
    }
}
