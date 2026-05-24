package com.agentframework.action;

import com.agentframework.action.middleware.*;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.*;
import com.agentframework.observability.EventSink;
import com.agentframework.security.SecurityEnforcer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Default {@link Action} implementation wiring a 5-layer validation stack,
 * middleware pipeline, and parallel fan-out executor.
 *
 * <h3>Validation stack (in order)</h3>
 * <ol>
 *   <li>{@link SchemaActionValidator}   — arity and type checks</li>
 *   <li>{@link SemanticActionValidator} — semantic consistency</li>
 *   <li>{@link SafetyActionValidator}   — pre-execution approval gate</li>
 *   <li>{@link SecurityEnforcer}        — policy hard-fail</li>
 *   <li>{@link TaintActionValidator}    — HOSTILE taint propagation block</li>
 * </ol>
 *
 * <p><b>DA-1 fix:</b> {@link #executeParallel} now calls
 * {@link SecurityEnforcer#validateParallel} before the fan-out, enforcing
 * hostile-taint and irreversible-action checks for parallel batches (IC6
 * integration).
 */
public class DefaultAction implements Action, AutoCloseable {

    private final ToolRegistry          registry;
    private final List<ActionValidator> validators;
    private final ToolMiddleware        middleware;
    private final ToolDispatcher        dispatcher;
    private final ExecutorService       executor;

    /**
     * Reference to the {@link SecurityEnforcer} extracted from the validator
     * list so {@link #executeParallel} can call {@code validateParallel}
     * directly (DA-1 fix).
     */
    private final SecurityEnforcer securityEnforcer;

    /**
     * Canonical factory — wires the full validation stack.
     *
     * @param eventSink the shared event sink used throughout the framework;
     *                  forwarded to {@link TaintActionValidator} for audit events.
     */
    public static DefaultAction withDefaultValidators(
            ToolRegistry registry,
            ToolMiddleware middleware,
            ToolDispatcher dispatcher,
            SecurityEnforcer securityEnforcer,
            EventSink eventSink) {
        return new DefaultAction(registry,
            List.of(
                new SchemaActionValidator(),
                new SemanticActionValidator(),
                new SafetyActionValidator(),
                securityEnforcer,
                new TaintActionValidator(eventSink)),
            middleware, dispatcher, securityEnforcer);
    }

    public DefaultAction(ToolRegistry registry, List<ActionValidator> validators,
                         ToolMiddleware middleware, ToolDispatcher dispatcher) {
        this(registry, validators, middleware, dispatcher,
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "tool-exec");
                t.setDaemon(true);
                return t;
            }), null);
    }

    public DefaultAction(ToolRegistry registry, List<ActionValidator> validators,
                         ToolMiddleware middleware, ToolDispatcher dispatcher,
                         SecurityEnforcer securityEnforcer) {
        this(registry, validators, middleware, dispatcher,
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "tool-exec");
                t.setDaemon(true);
                return t;
            }), securityEnforcer);
    }

    public DefaultAction(ToolRegistry registry, List<ActionValidator> validators,
                         ToolMiddleware middleware, ToolDispatcher dispatcher,
                         ExecutorService executor) {
        this(registry, validators, middleware, dispatcher, executor, null);
    }

    public DefaultAction(ToolRegistry registry, List<ActionValidator> validators,
                         ToolMiddleware middleware, ToolDispatcher dispatcher,
                         ExecutorService executor, SecurityEnforcer securityEnforcer) {
        this.registry         = registry;
        this.validators       = validators;
        this.middleware       = middleware;
        this.dispatcher       = dispatcher;
        this.executor         = executor;
        this.securityEnforcer = securityEnforcer;
    }

    public ActionResult execute(Decision decision, ExecutionContext ctx) {
        return switch (decision) {
            case ToolCall tc         -> executeToolCall(tc, ctx);
            case ParallelToolCalls p -> executeParallel(p, ctx);
            case FinalAnswer fa      -> ActionResult.success(
                new ToolResult(fa.content(), List.of(), 0, BigDecimal.ZERO, Duration.ZERO, 0));
            case Escalate e          -> ActionResult.failure("ESCALATED", e.reason());
            case AskClarification q  -> ActionResult.failure("CLARIFY", q.question());
        };
    }

    /**
     * DA-2 fix: shut down the executor when this action instance is closed.
     */
    @Override
    public void close() {
        executor.shutdownNow();
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
            ctx.addTokens(r.tokensUsed());
            ctx.addCost(r.cost());
            return ActionResult.success(r);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() instanceof ToolException te ? te : e;
            String code = cause instanceof ToolException te2 ? te2.errorCode() : "TOOL_ERROR";
            return ActionResult.failure(code, cause.getMessage());
        }
    }

    /**
     * DA-1 fix: call {@link SecurityEnforcer#validateParallel} before the fan-out
     * so hostile-taint and irreversible-action checks cover the whole batch.
     */
    private ActionResult executeParallel(ParallelToolCalls p, ExecutionContext ctx) {
        List<ToolCall> calls = p.calls();

        // DA-1 fix: batch-level security check via SecurityEnforcer.validateParallel
        if (securityEnforcer != null) {
            ValidationVerdict sv = securityEnforcer.validateParallel(
                calls, registry::lookup, ctx);
            if (!sv.isPassed()) return ActionResult.validationFailure(sv);
        }

        // Per-call validation through the full validator chain
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
        long             deadline = p.deadline().toMillis();
        long             start    = System.currentTimeMillis();

        for (int i = 0; i < futures.size(); i++) {
            // DA-3 improvement: use remaining wall-clock time rather than resetting
            // the full deadline per future, capping at 1 ms to avoid negative timeouts.
            long elapsed   = System.currentTimeMillis() - start;
            long remaining = Math.max(1L, deadline - elapsed);
            try {
                results.add(futures.get(i).get(remaining, TimeUnit.MILLISECONDS));
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
