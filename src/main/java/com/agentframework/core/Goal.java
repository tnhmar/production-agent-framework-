package com.agentframework.core;

import java.util.List;

/**
 * Immutable goal record on the agent's goal stack.
 *
 * <p>{@code successCriteria} is an optional free-text field that the plan
 * validator can use for lightweight policy decisions — e.g. exclusion tokens
 * of the form {@code !toolName} prevent specific tools from being dispatched
 * while this goal is active.  {@code null} / blank means no additional constraints.
 */
public record Goal(
        String       id,
        String       parentId,
        GoalStatus   status,
        String       description,
        List<String> dependencies,
        Budget       allocatedBudget,
        String       successCriteria) {

    /** Compact constructor — normalises null successCriteria to empty string. */
    public Goal {
        if (successCriteria == null) successCriteria = "";
    }

    /** Backward-compatible factory used throughout the existing codebase (no criteria). */
    public Goal(String id, String parentId, GoalStatus status,
                String description, List<String> dependencies, Budget allocatedBudget) {
        this(id, parentId, status, description, dependencies, allocatedBudget, "");
    }

    public Goal withStatus(GoalStatus s) {
        return new Goal(id, parentId, s, description, dependencies, allocatedBudget, successCriteria);
    }

    public Goal withSuccessCriteria(String criteria) {
        return new Goal(id, parentId, status, description, dependencies, allocatedBudget, criteria);
    }
}
