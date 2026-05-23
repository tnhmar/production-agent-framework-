package com.agentframework.action.middleware;

import com.agentframework.foundation.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Factory that assembles the canonical tool-middleware chain.
 *
 * <h3>Canonical order</h3>
 * <pre>
 *   Logging → CircuitBreaker → RateLimiting → Caching → Retry → handler
 * </pre>
 * The order is critical:
 * <ul>
 *   <li><b>Logging outermost</b>: captures every attempt, retry, and final outcome.</li>
 *   <li><b>CircuitBreaker before RateLimit</b>: a tripped circuit short-circuits
 *       immediately without consuming a rate-limit token.</li>
 *   <li><b>Caching before Retry</b>: a cache hit avoids a round-trip entirely,
 *       so the retry layer never sees a request that can be served from cache.</li>
 *   <li><b>Retry innermost</b>: only terminal failures (after the circuit is checked)
 *       are retried; the circuit counter sees only final-attempt outcomes.</li>
 * </ul>
 *
 * <p>This class is {@code final} and non-instantiable; all methods are static.
 */
public final class ToolMiddlewarePipeline {

    private ToolMiddlewarePipeline() { throw new AssertionError("Not instantiable"); }

    /**
     * Assembles the full canonical pipeline.
     *
     * @param logging        outermost stage — always present
     * @param circuitBreaker second stage
     * @param rateLimiting   third stage
     * @param caching        fourth stage
     * @param retry          fifth stage
     * @param handler        the actual tool executor — innermost stage
     * @return the composed {@link ToolMiddleware} ready for use
     */
    public static ToolMiddleware standard(
            LoggingMiddleware        logging,
            CircuitBreakerMiddleware circuitBreaker,
            RateLimitingMiddleware   rateLimiting,
            CachingMiddleware        caching,
            RetryMiddleware          retry,
            Function<ToolInvocation, ToolResult> handler) {

        Objects.requireNonNull(logging,       "logging must not be null");
        Objects.requireNonNull(circuitBreaker, "circuitBreaker must not be null");
        Objects.requireNonNull(rateLimiting,  "rateLimiting must not be null");
        Objects.requireNonNull(caching,       "caching must not be null");
        Objects.requireNonNull(retry,         "retry must not be null");
        Objects.requireNonNull(handler,       "handler must not be null");

        // Build from the inside out: retry wraps handler, caching wraps retry, …
        ToolMiddleware chain = (inv, next) -> retry.apply(inv, handler::apply);
        chain = wrap(caching,        chain);
        chain = wrap(rateLimiting,   chain);
        chain = wrap(circuitBreaker, chain);
        chain = wrap(logging,        chain);
        return chain;
    }

    /**
     * Fluent builder for non-standard pipeline configurations.
     * Stages are applied outermost-first in the order they are added.
     */
    public static Builder builder(Function<ToolInvocation, ToolResult> handler) {
        return new Builder(handler);
    }

    private static ToolMiddleware wrap(ToolMiddleware outer, ToolMiddleware inner) {
        return (inv, ignored) -> outer.apply(inv, i -> inner.apply(i, null));
    }

    public static final class Builder {
        private final Function<ToolInvocation, ToolResult> handler;
        private final List<ToolMiddleware> stages = new ArrayList<>();

        private Builder(Function<ToolInvocation, ToolResult> handler) {
            this.handler = Objects.requireNonNull(handler, "handler must not be null");
        }

        /**
         * Adds a stage.  The first stage added is the outermost (first to receive
         * the invocation, last to process the result).
         */
        public Builder addStage(ToolMiddleware stage) {
            stages.add(Objects.requireNonNull(stage, "stage must not be null"));
            return this;
        }

        public ToolMiddleware build() {
            if (stages.isEmpty()) {
                return (inv, next) -> handler.apply(inv);
            }
            // Innermost: retry stage (or last added) wraps the handler
            ToolMiddleware chain = (inv, ignored) -> handler.apply(inv);
            for (int i = stages.size() - 1; i >= 0; i--) {
                chain = wrap(stages.get(i), chain);
            }
            return chain;
        }
    }
}
