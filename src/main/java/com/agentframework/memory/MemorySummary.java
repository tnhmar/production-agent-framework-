package com.agentframework.memory;
import java.time.Instant; import java.util.List;
public record MemorySummary(String sessionId, String compressedText,
        List<String> sourceIds, Instant createdAt) {}
