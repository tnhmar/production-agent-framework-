package com.agentframework.memory;

/**
 * A single ANN search result returned by {@link VectorStore#search}.
 *
 * @param id      vector ID in the store
 * @param score   similarity score (higher = more similar)
 * @param payload associated text payload; empty string if not retrieved
 */
public record Neighbor(String id, double score, String payload) {

    /** Convenience factory for callers that do not need the payload. */
    public static Neighbor of(String id, double score) {
        return new Neighbor(id, score, "");
    }
}
