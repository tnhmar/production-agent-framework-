package com.agentframework.foundation;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
public record Task(
        String     instruction,
        int        maxCycles,
        int        maxTokens,
        Duration   maxWallClockTime,
        BigDecimal budgetLimit) {
    public Task { Objects.requireNonNull(instruction, "instruction required"); }
    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private String     instruction;
        private int        maxCycles        = 50;
        private int        maxTokens        = 200_000;
        private Duration   maxWallClockTime = Duration.ofMinutes(10);
        private BigDecimal budgetLimit      = BigDecimal.valueOf(100);
        public Builder instruction(String i)      { instruction=i;        return this; }
        public Builder maxCycles(int c)           { maxCycles=c;          return this; }
        public Builder maxTokens(int t)           { maxTokens=t;          return this; }
        public Builder maxWallClockTime(Duration d){ maxWallClockTime=d;  return this; }
        public Builder budgetLimit(BigDecimal b)   { budgetLimit=b;        return this; }
        public Task build() { return new Task(instruction,maxCycles,maxTokens,maxWallClockTime,budgetLimit); }
    }
}
