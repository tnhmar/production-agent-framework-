package com.agentframework.memory;
import java.util.List; import java.util.Map;
public record MemoryContent(String text, Map<String,Object> structured, List<Double> embedding) {
    public static MemoryContent text(String t) { return new MemoryContent(t, Map.of(), null); }
}
