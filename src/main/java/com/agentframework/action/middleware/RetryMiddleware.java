package com.agentframework.action.middleware;

import com.agentframework.action.OperationalParams;
import com.agentframework.foundation.ToolResult;

import java.util.Random;
import java.util.function.Function;

/**
 * Retry middleware with <em>true exponential backoff and jitter</em>.
 *
 * <h3>Backoff formula</h3>
 * <pre>
 *   sleepMs = min(baseMs * 2^(attempt-1), maxBackoffMs) + jitter
 *   jitter  = random in [-0.3 * sleepMs, +0.3 * sleepMs]
 * </pre>
 * The jitter window is ±30% of the raw exponential value.  This desynchronises
 * retries from multiple concurrent agents that fail at the same instant, preventing
 * a thundering-herd stampede to the downstream service.
 *
 * <h3>Safety guards</h3>
 * <ul>
 *   <li>Non-idempotent tools are hard-capped at 0 retries — automatically derived
 *       from {@link OperationalParams#idempotent()} when present in the invocation
 *       contract, falling back to construction-time defaults.</li>
 *   <li>{@link Thread#interrupt()} is honoured: interrupted sleep re-raises the
 *       current exception immediately rather than silently swallowing the signal.</li>
 * </ul>
 */
public class RetryMiddleware implements ToolMiddleware {

    private static final double JITTER_FACTOR  = 0.30;
    private static final long   DEFAULT_MAX_BACKOFF_MS = 30_000L;

    private final int    defaultMaxRetries;
    private final long   baseBackoffMs;
    private final long   maxBackoffMs;
    private final Random jitterRandom;

    public RetryMiddleware(int defaultMaxRetries, long baseBackoffMs) {
        this(defaultMaxRetries, baseBackoffMs, DEFAULT_MAX_BACKOFF_MS, new Random());
    }

    /**
     * Full constructor — allows injecting {@code maxBackoffMs} and a seeded
     * {@link Random} for deterministic unit tests.
     */
    public RetryMiddleware(int defaultMaxRetries, long baseBackoffMs,
                           long maxBackoffMs, Random jitterRandom) {
        if (defaultMaxRetries < 0) throw new IllegalArgumentException("defaultMaxRetries must be >= 0");
        if (baseBackoffMs <= 0)    throw new IllegalArgumentException("baseBackoffMs must be > 0");
        if (maxBackoffMs < baseBackoffMs) throw new IllegalArgumentException(
            "maxBackoffMs must be >= baseBackoffMs");
        this.defaultMaxRetries = defaultMaxRetries;
        this.baseBackoffMs     = baseBackoffMs;
        this.maxBackoffMs      = maxBackoffMs;
        this.jitterRandom      = jitterRandom;
    }

    @Override
    public ToolResult apply(ToolInvocation inv, Function<ToolInvocation, ToolResult> next) {
        OperationalParams params = (inv.contract() != null) ? inv.contract().operationalParams() : null;
        int maxRetries = (params != null) ? params.maxRetries() : defaultMaxRetries;

        if (params != null && !params.idempotent() && maxRetries > 0) {
            maxRetries = 0;
        }

        int attempt = 0;
        while (true) {
            try {
                ToolResult raw = next.apply(inv);
                return (attempt == 0) ? raw : raw.withRetryCount(attempt);
            } catch (RuntimeException e) {
                if (++attempt > maxRetries) throw e;
                sleep(computeBackoff(attempt));
            }
        }
    }

    /**
     * Computes the sleep duration for {@code attempt} (1-based).
     * Formula: min(base * 2^(attempt-1), max) ± 30% jitter.
     */
    private long computeBackoff(int attempt) {
        long exponential = baseBackoffMs * (1L << (attempt - 1));
        long capped       = Math.min(exponential, maxBackoffMs);
        long jitter       = (long) ((jitterRandom.nextDouble() * 2 - 1) * JITTER_FACTOR * capped);
        return Math.max(1L, capped + jitter);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry sleep interrupted", ie);
        }
    }
}
