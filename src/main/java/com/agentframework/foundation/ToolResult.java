package com.agentframework.foundation;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Immutable result of a single tool invocation.
 *
 * <p>{@code retryCount} records how many attempts the retry middleware made
 * before returning this result. 0 = first attempt succeeded.
 * {@link RetryMiddleware} calls {@link #withRetryCount(int)} to attach
 * the attempt count before returning to the action layer.
 */
public record ToolResult(
        Object           data,
        List<SideEffect> sideEffects,
        int              tokensUsed,
        BigDecimal       cost,
        Duration         duration,
        int              retryCount) {

    public boolean indicatesWorldChange() {
        return sideEffects.stream().anyMatch(e -> e != SideEffect.READ_ONLY);
    }

    /** Factory — first-attempt success, read-only side effect, zero retries. */
    public static ToolResult ok(Object data) {
        return new ToolResult(data, List.of(SideEffect.READ_ONLY),
                              0, BigDecimal.ZERO, Duration.ZERO, 0);
    }

    /** Factory — first-attempt write, non-idempotent, zero retries. */
    public static ToolResult write(Object data) {
        return new ToolResult(data, List.of(SideEffect.WRITE_NON_IDEMPOTENT),
                              0, BigDecimal.ZERO, Duration.ZERO, 0);
    }

    /** Factory — rejected by approval / validation, zero retries. */
    public static ToolResult rejected(String reason) {
        return new ToolResult("REJECTED:" + reason, List.of(),
                              0, BigDecimal.ZERO, Duration.ZERO, 0);
    }

    /**
     * Returns a copy of this result with the given retry count attached.
     * Called by {@code RetryMiddleware} after the final attempt completes.
     */
    public ToolResult withRetryCount(int retries) {
        return new ToolResult(data, sideEffects, tokensUsed, cost, duration, retries);
    }
}
