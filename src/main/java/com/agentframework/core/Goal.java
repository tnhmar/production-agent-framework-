package com.agentframework.core;
import java.util.List;
public record Goal(String id, String parentId, GoalStatus status,
                   String description, List<String> dependencies, Budget allocatedBudget) {
    public Goal withStatus(GoalStatus s) {
        return new Goal(id, parentId, s, description, dependencies, allocatedBudget);
    }
}
