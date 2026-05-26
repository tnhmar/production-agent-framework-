package com.agentframework.core;

/**
 * Evicts working-memory entries when context usage exceeds a configurable
 * fraction of the token budget.
 *
 * <h3>M5 fix — injectable eviction threshold</h3>
 * <p>Previously {@code EVICTION_THRESHOLD} was a hardcoded
 * {@code private static final double}.  Deployments using smaller models
 * (32k context) or larger models (200k+ context) may need different eviction
 * points but had no way to tune the value without recompilation.
 *
 * <p>The threshold is now an injectable instance field:
 * <ul>
 *   <li>Zero-arg constructor defaults to {@link #DEFAULT_EVICTION_THRESHOLD}
 *       (0.70) — fully backward compatible.</li>
 *   <li>Single-arg constructor accepts any value in {@code (0.0, 1.0]};
 *       throws {@link IllegalArgumentException} on out-of-range input.</li>
 *   <li>{@link #withThreshold(double)} static factory for fluent construction.</li>
 * </ul>
 */
public class ContextWindowManager {

    /** Default fraction of the token budget at which eviction is triggered. */
    public static final double DEFAULT_EVICTION_THRESHOLD = 0.70;

    private final double evictionThreshold;

    /**
     * Creates a manager using the default eviction threshold
     * ({@value #DEFAULT_EVICTION_THRESHOLD}).
     * Backward-compatible zero-arg constructor (M5 fix).
     */
    public ContextWindowManager() {
        this(DEFAULT_EVICTION_THRESHOLD);
    }

    /**
     * Creates a manager with the specified eviction threshold.
     *
     * @param evictionThreshold fraction of {@code maxTokens} at which eviction
     *                          is triggered; must be in {@code (0.0, 1.0]}
     * @throws IllegalArgumentException if the threshold is out of range
     */
    public ContextWindowManager(double evictionThreshold) {
        if (evictionThreshold <= 0.0 || evictionThreshold > 1.0)
            throw new IllegalArgumentException(
                "evictionThreshold must be in (0.0, 1.0], got: " + evictionThreshold);
        this.evictionThreshold = evictionThreshold;
    }

    /**
     * Static factory for fluent construction (M5 fix).
     *
     * @param threshold eviction threshold in {@code (0.0, 1.0]}
     */
    public static ContextWindowManager withThreshold(double threshold) {
        return new ContextWindowManager(threshold);
    }

    /**
     * Evicts the lowest-relevance half of working-memory entries when
     * token usage exceeds the configured threshold.
     *
     * @param wm        working memory to manage
     * @param maxTokens maximum token budget for this context window
     */
    public void manage(WorkingMemory wm, int maxTokens) {
        if (maxTokens <= 0) return;
        if (wm.estimatedTokenCount() > (int)(evictionThreshold * maxTokens)) {
            int half = Math.max(1, wm.size() / 2);
            wm.evictLowestRelevance(half);
        }
    }
}
