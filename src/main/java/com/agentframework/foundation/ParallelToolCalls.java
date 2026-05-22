package com.agentframework.foundation;
import java.time.Duration;
import java.util.List;
public record ParallelToolCalls(
        List<ToolCall> calls,
        boolean requireAll,
        Duration deadline) implements Decision {}
