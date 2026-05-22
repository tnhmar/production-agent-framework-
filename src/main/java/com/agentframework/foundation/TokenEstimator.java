package com.agentframework.foundation;
/** Estimates token count without external dependencies (chars/4 heuristic). */
public interface TokenEstimator {
    int estimate(String text);
    String truncate(String text, int maxTokens);
    /** Default implementation — no external libs required. */
    static TokenEstimator heuristic() {
        return new TokenEstimator() {
            public int estimate(String t)           { return t == null ? 0 : t.length() / 4; }
            public String truncate(String t, int m) { return t == null ? "" : t.substring(0, Math.min(t.length(), m*4)); }
        };
    }
}
