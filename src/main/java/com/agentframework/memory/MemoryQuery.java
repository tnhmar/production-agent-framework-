package com.agentframework.memory;
import java.util.List; import java.util.Map;
public record MemoryQuery(String naturalLanguage, List<Double> embedding,
        MemoryType type, Map<String,String> filters, double minScore) {
    public static MemoryQuery text(String q) {
        return new MemoryQuery(q, null, null, Map.of(), 0.0);
    }
}
