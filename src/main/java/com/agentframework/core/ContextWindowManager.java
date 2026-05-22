package com.agentframework.core;
/** Evicts working-memory entries when context grows beyond 70 % of the token budget. */
public class ContextWindowManager {
    private static final double EVICTION_THRESHOLD = 0.70;
    public void manage(WorkingMemory wm, int maxTokens) {
        if (maxTokens <= 0) return;
        if (wm.estimatedTokenCount() > (int)(EVICTION_THRESHOLD * maxTokens)) {
            int half = Math.max(1, wm.size() / 2);
            wm.evictLowestRelevance(half);
        }
    }
}
