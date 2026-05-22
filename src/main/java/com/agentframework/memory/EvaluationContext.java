package com.agentframework.memory;
import com.agentframework.core.Goal;
import java.time.Instant; import java.util.List;
public record EvaluationContext(Instant now, Goal currentGoal, List<String> activeTaskTags) {
    public static EvaluationContext current() {
        return new EvaluationContext(Instant.now(), null, List.of());
    }
    public static EvaluationContext withGoal(Goal goal) {
        return new EvaluationContext(Instant.now(), goal, List.of());
    }
}
