package com.agentframework.memory;
import java.time.Instant; import java.util.Set;
public record MemoryMetadata(double importanceScore, Instant createdAt, String source, Set<String> tags) {
    public static MemoryMetadata of(double importance, String source) {
        return new MemoryMetadata(importance, Instant.now(), source, Set.of());
    }
}
