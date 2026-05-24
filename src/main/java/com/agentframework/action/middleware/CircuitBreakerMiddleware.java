package com.agentframework.action.middleware;

import com.agentframework.foundation.ToolResult;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Thread-safe circuit breaker middleware.
 *
 * <p>Uses {@link AtomicBoolean} for the open flag to eliminate the
 * volatile compound check-then-act race condition flagged by SpotBugs
 * (IS2_INCONSISTENT_SYNC / VO_VOLATILE_INCREMENT).</p>
 */
public class CircuitBreakerMiddleware implements ToolMiddleware {

    private final int  failureThreshold;
    private final long openTimeoutMs;

    private final AtomicInteger failureCount   = new AtomicInteger();
    private final AtomicLong    lastFailureTime = new AtomicLong();
    private final AtomicBoolean open           = new AtomicBoolean(false);

    public CircuitBreakerMiddleware(int failureThreshold, long openTimeoutMs) {
        this.failureThreshold = failureThreshold;
        this.openTimeoutMs    = openTimeoutMs;
    }

    @Override
    public ToolResult apply(ToolInvocation inv, Function<ToolInvocation, ToolResult> next) {
        if (open.get()) {
            if (System.currentTimeMillis() - lastFailureTime.get() > openTimeoutMs) {
                open.set(false);
                failureCount.set(0);
            } else {
                String toolName = (inv.contract() != null) ? inv.contract().name() : "unknown";
                throw new RuntimeException("Circuit open for " + toolName);
            }
        }
        try {
            ToolResult r = next.apply(inv);
            failureCount.set(0);
            return r;
        } catch (RuntimeException e) {
            if (failureCount.incrementAndGet() >= failureThreshold) {
                open.set(true);
                lastFailureTime.set(System.currentTimeMillis());
            }
            throw e;
        }
    }

    public boolean isOpen() { return open.get(); }

    public void reset() {
        open.set(false);
        failureCount.set(0);
    }
}
