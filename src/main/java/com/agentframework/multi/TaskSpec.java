package com.agentframework.multi;
import java.util.Map;
public record TaskSpec(String objective, String outputFormat, Map<String,Object> context) {
    public static TaskSpec of(String objective) { return new TaskSpec(objective, "text", Map.of()); }
}
