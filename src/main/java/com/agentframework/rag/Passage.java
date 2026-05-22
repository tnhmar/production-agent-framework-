package com.agentframework.rag;
import java.time.Instant; import java.util.Map;
public record Passage(String id, String text, String title, String sourceId,
        double score, Instant date, Map<String,String> metadata) {}
