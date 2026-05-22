package com.agentframework.memory;
public record MemoryRecord(String id, MemoryContent content, MemoryType type,
        MemoryMetadata meta, double score, int accessCount) {
    public MemoryRecord withAccessCount(int n) {
        return new MemoryRecord(id, content, type, meta, score, n);
    }
    public MemoryRecord withScore(double s) {
        return new MemoryRecord(id, content, type, meta, s, accessCount);
    }
}
