package com.agentframework.foundation;
import java.util.Map;
public record ToolCall(
        String toolName,
        Map<String, Object> arguments,
        String reasoningTrace) implements Decision {}
