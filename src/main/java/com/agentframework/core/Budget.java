package com.agentframework.core;
import java.math.BigDecimal; import java.time.Duration;
public record Budget(int maxCycles, int maxTokens, Duration maxTime, BigDecimal maxCost) {
    public static Budget unlimited() {
        return new Budget(Integer.MAX_VALUE, Integer.MAX_VALUE,
                          Duration.ofHours(24), BigDecimal.valueOf(Long.MAX_VALUE));
    }
}
