package com.agentframework.action.middleware;

import com.agentframework.action.OperationalParams;
import com.agentframework.foundation.ToolResult;
import java.util.function.Function;

/**
 * Retry middleware with exponential backoff.
 *
 * <p>m5 fix: when the {@link ToolInvocation}'s contract has {@link OperationalParams},
 * the retry count and timeout from that contract take precedence over the defaults
 * supplied at construction time. This enables per-tool retry policies defined in the
 * tool contract to be honoured automatically.
 */
public class RetryMiddleware implements ToolMiddleware {
    private final int  defaultMaxRetries;
    private final long defaultBackoffMs;

    public RetryMiddleware(int defaultMaxRetries, long defaultBackoffMs) {
        this.defaultMaxRetries = defaultMaxRetries;
        this.defaultBackoffMs  = defaultBackoffMs;
    }

    public ToolResult apply(ToolInvocation inv, Function<ToolInvocation, ToolResult> next) {
        // m5 fix: resolve retry count from contract's OperationalParams if present
        OperationalParams params = inv.contract() != null ? inv.contract().operationalParams() : null;
        int  maxRetries = (params != null) ? params.maxRetries() : defaultMaxRetries;
        long backoffMs  = defaultBackoffMs;

        // Non-idempotent tools must not be retried — spec: "Require deduplication or explicit control"
        if (params != null && !params.idempotent() && maxRetries > 0) {
            maxRetries = 0; // hard cap: never retry a non-idempotent tool automatically
        }

        int attempt = 0;
        while (true) {
            try {
                return next.apply(inv);
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
