package com.agentframework.action.middleware;

import com.agentframework.action.OperationalParams;
import com.agentframework.foundation.ToolResult;
import java.util.function.Function;

/**
 * Retry middleware with exponential backoff.
 *
 * <p>m5 fix: per-tool retry policy from {@link OperationalParams} takes precedence
 * over construction-time defaults.  Non-idempotent tools are hard-capped at 0 retries.
 *
 * <p>N4 fix: the final {@link ToolResult} carries the actual retry count via
 * {@link ToolResult#withRetryCount(int)} so the audit trail in {@code CycleRecord}
 * can distinguish a first-attempt success from a success after N retries.
 */
public class RetryMiddleware implements ToolMiddleware {
    private final int  defaultMaxRetries;
    private final long defaultBackoffMs;

    public RetryMiddleware(int defaultMaxRetries, long defaultBackoffMs) {
        this.defaultMaxRetries = defaultMaxRetries;
        this.defaultBackoffMs  = defaultBackoffMs;
    }

    @Override
    public ToolResult apply(ToolInvocation inv, Function<ToolInvocation, ToolResult> next) {
        OperationalParams params = inv.contract() != null ? inv.contract().operationalParams() : null;
        int  maxRetries = (params != null) ? params.maxRetries() : defaultMaxRetries;
        long backoffMs  = defaultBackoffMs;

        // Non-idempotent tools must never be retried automatically
        if (params != null && !params.idempotent() && maxRetries > 0) {
            maxRetries = 0;
        }

        int attempt = 0;
        while (true) {
            try {
                ToolResult raw = next.apply(inv);
                // N4 fix: attach attempt count; 0 means first-attempt success
                return attempt == 0 ? raw : raw.withRetryCount(attempt);
            } catch (RuntimeException e) {
                if (++attempt > maxRetries) throw e;
                try {
                    Thread.sleep(backoffMs * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
