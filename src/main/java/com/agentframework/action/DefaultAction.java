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
 * <h3>Validation stack order (C3 fix — spec Vol. 1, Ch. 7)</h3>
 * <ol>
 *   <li>{@link SchemaActionValidator}   — arity and type checks</li>
 *   <li>{@link SemanticActionValidator} — semantic consistency</li>
 *   <li>{@link SecurityEnforcer}        — policy hard-fail (FAILED) — must run
 *       <em>before</em> SafetyActionValidator so policy-blocked calls are
 *       rejected outright rather than pausing for operator approval</li>
 *   <li>{@link SafetyActionValidator}   — pre-execution approval gate
 *       (REQUIRE_APPROVAL) — runs after policy so approval is only requested
 *       for calls that policy has already permitted</li>
 *   <li>{@link TaintActionValidator}    — HOSTILE taint propagation block</li>
 * </ol>
 *
 * <p><b>DA-1 fix:</b> {@link #executeParallel} calls
 * {@link SecurityEnforcer#validateParallel} before the fan-out, enforcing
 * hostile-taint and irreversible-action checks for parallel batches.
 *
 * <p><b>M4 fix:</b> thread-pool creation is centralised in
 * {@link #newDefaultExecutor()} — {@code newFixedThreadPool} appears exactly
 * once in this file.
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

    // ── Factory ─────────────────────────────────────────────────────────────

    /**
     * Canonical factory — wires the full validation stack in the order
     * mandated by spec Vol. 1, Ch. 7 (C3 fix).
     *
     * <p>Stack order: Schema → Semantic → Policy ({@link SecurityEnforcer})
     * → Safety ({@link SafetyActionValidator}) → Taint
     *
     * @param eventSink the shared event sink forwarded to {@link TaintActionValidator}.
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
                securityEnforcer,               // position 3 — C3 fix: policy before safety
                new SafetyActionValidator(),    // position 4 — C3 fix: safety after policy
                new TaintActionValidator(eventSink)),
            middleware, dispatcher, securityEnforcer);
    }

    // ── Constructors ─────────────────────────────────────────────────────────

    /**
     * Short constructor — no {@link SecurityEnforcer}.
     * Delegates to canonical 6-arg form via {@link #newDefaultExecutor()} (M4 fix).
     */
    public DefaultAction(ToolRegistry registry, List<ActionValidator> validators,
                         ToolMiddleware middleware, ToolDispatcher dispatcher) {
        this(registry, validators, middleware, dispatcher, newDefaultExecutor(), null);
    }

    /**
     * Short constructor — with {@link SecurityEnforcer}.
     * Delegates to canonical 6-arg form via {@link #newDefaultExecutor()} (M4 fix).
     */
    public DefaultAction(ToolRegistry registry, List<ActionValidator> validators,
                         ToolMiddleware middleware, ToolDispatcher dispatcher,
                         SecurityEnforcer securityEnforcer) {
        this(registry, validators, middleware, dispatcher, newDefaultExecutor(), securityEnforcer);
    }

    /** Short constructor — custom executor, no {@link SecurityEnforcer}. */
    public DefaultAction(ToolRegistry registry, List<ActionValidator> validators,
                         ToolMiddleware middleware, ToolDispatcher dispatcher,
                         ExecutorService executor) {
        this(registry, validators, middleware, dispatcher, executor, null);
    }

    /** Canonical 6-arg constructor — all other constructors delegate here. */
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

    // ── Private factory — M4 fix ─────────────────────────────────────────────

    /**
     * Creates the default fixed thread pool used by short constructors.
     * Centralised here so pool size, thread name, and daemon flag are
     * maintained in exactly one place (M4 fix — DRY).
     */
    private static ExecutorService newDefaultExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "tool-exec");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Public API ───────────────────────────────────────────────────────────

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

    // ── Private helpers ──────────────────────────────────────────────────────

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

        if (securityEnforcer != null) {
            ValidationVerdict sv = securityEnforcer.validateParallel(
                calls, registry::lookup, ctx);
            if (!sv.isPassed()) return ActionResult.validationFailure(sv);
        }

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
