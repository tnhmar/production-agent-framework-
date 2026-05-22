package com.agentframework.observability;
import java.math.BigDecimal;
public record MetricsSnapshot(String runId, int cycles, int totalTokens,
        BigDecimal totalCost, long durationMs, int toolCalls, int toolFailures) {}
