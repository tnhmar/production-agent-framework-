package com.agentframework.foundation;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable description of the work an agent is asked to perform, together with
 * all resource limits that bound its execution.
 *
 * <p>{@code maxChainDepth} (default 10) caps how many levels of agent-to-agent
 * delegation are permitted before the runtime aborts with
 * {@link TerminationReason.ResourceLimit}. A value of 0 means "use the runtime
 * default" — {@code StateMachineRunner} treats 0 as {@code DEFAULT_MAX_CHAIN_DEPTH}.
 */
public record Task(
        String     instruction,
        int        maxCycles,
        int        maxTokens,
        Duration   maxWallClockTime,
        BigDecimal budgetLimit,
        int        maxChainDepth) {

    /** Compact canonical constructor — validates required fields. */
    public Task {
        Objects.requireNonNull(instruction, "instruction required");
        if (maxCycles  <= 0) throw new IllegalArgumentException("maxCycles must be > 0");
        if (maxTokens  <= 0) throw new IllegalArgumentException("maxTokens must be > 0");
        if (maxChainDepth < 0) throw new IllegalArgumentException("maxChainDepth must be >= 0");
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String     instruction;
        private int        maxCycles        = 50;
        private int        maxTokens        = 200_000;
        private Duration   maxWallClockTime = Duration.ofMinutes(10);
        private BigDecimal budgetLimit      = BigDecimal.valueOf(100);
        private int        maxChainDepth    = 10;   // 0 → runtime default

        public Builder instruction(String i)       { instruction = i;       return this; }
        public Builder maxCycles(int c)            { maxCycles = c;         return this; }
        public Builder maxTokens(int t)            { maxTokens = t;         return this; }
        public Builder maxWallClockTime(Duration d){ maxWallClockTime = d;  return this; }
        public Builder budgetLimit(BigDecimal b)   { budgetLimit = b;       return this; }
        public Builder maxChainDepth(int d)        { maxChainDepth = d;     return this; }

        public Task build() {
            return new Task(instruction, maxCycles, maxTokens,
                            maxWallClockTime, budgetLimit, maxChainDepth);
        }
    }
}
