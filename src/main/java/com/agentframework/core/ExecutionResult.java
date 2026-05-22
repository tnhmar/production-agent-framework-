package com.agentframework.core;
import com.agentframework.foundation.*;
import java.math.BigDecimal;
import java.util.List;
public record ExecutionResult(
        List<CycleRecord> cycleRecords,
        RunState          finalState,
        TerminationReason terminationReason,
        int               totalTokens,
        BigDecimal        totalCost,
        String            finalAnswer) {
    public static ExecutionResult from(ExecutionContext ctx) {
        // Extract final answer from last successful FinalAnswer decision
        String answer = ctx.trace().stream()
            .filter(r -> r.decision() instanceof FinalAnswer)
            .reduce((a,b)->b)
            .map(r -> ((FinalAnswer) r.decision()).content())
            .orElse(null);
        return new ExecutionResult(ctx.trace(), ctx.currentState(),
            ctx.terminationReason().orElse(null),
            ctx.totalTokensUsed(), ctx.totalCost(), answer);
    }
    public boolean succeeded() {
        return terminationReason instanceof TerminationReason.GoalCompleted;
    }
}
