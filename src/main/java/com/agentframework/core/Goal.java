package com.agentframework.core;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Immutable goal record on the agent's goal stack.
 *
 * <p>Tool constraints are expressed via typed {@code Set<String>} fields rather
 * than free-text conventions embedded in {@code successCriteria}:
 * <ul>
 *   <li>{@link #excludedTools} — the agent must NOT dispatch any tool whose
 *       name is in this set while this goal is active.</li>
 *   <li>{@link #requiredTools} — a non-empty set restricts dispatch to only
 *       the named tools (whitelist mode). An empty set means unrestricted.</li>
 * </ul>
 *
 * <p>All fields are validated at construction time. {@code null} collections are
 * normalised to empty immutable sets so callers never need defensive null-checks.
 */
public record Goal(
        String       id,
        String       parentId,
        GoalStatus   status,
        String       description,
        List<String> dependencies,
        Budget       allocatedBudget,
        String       successCriteria,
        Set<String>  excludedTools,
        Set<String>  requiredTools) {

    /** Compact canonical constructor — normalises nulls and defends against mutation. */
    public Goal {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Goal id must not be blank");
        if (successCriteria == null) successCriteria = "";
        excludedTools = excludedTools == null ? Set.of() : Collections.unmodifiableSet(excludedTools);
        requiredTools = requiredTools == null ? Set.of() : Collections.unmodifiableSet(requiredTools);
        dependencies  = dependencies  == null ? List.of() : List.copyOf(dependencies);
    }

    /** Full factory with all constraint fields. */
    public static Goal of(String id, String parentId, GoalStatus status,
                          String description, List<String> dependencies,
                          Budget allocatedBudget, String successCriteria,
                          Set<String> excludedTools, Set<String> requiredTools) {
        return new Goal(id, parentId, status, description, dependencies,
                        allocatedBudget, successCriteria, excludedTools, requiredTools);
    }

    /**
     * Backward-compatible factory for callers that predate the typed-constraint
     * fields. Creates a goal with empty excluded/required sets.
     */
    public Goal(String id, String parentId, GoalStatus status,
                String description, List<String> dependencies, Budget allocatedBudget) {
        this(id, parentId, status, description, dependencies,
             allocatedBudget, "", Set.of(), Set.of());
    }

    /**
     * Backward-compatible factory with successCriteria but no typed constraints.
     */
    public Goal(String id, String parentId, GoalStatus status,
                String description, List<String> dependencies,
                Budget allocatedBudget, String successCriteria) {
        this(id, parentId, status, description, dependencies,
             allocatedBudget, successCriteria, Set.of(), Set.of());
    }

    // ── Fluent immutable copy-methods ────────────────────────────────────────

    public Goal withStatus(GoalStatus s) {
        return new Goal(id, parentId, s, description, dependencies,
                        allocatedBudget, successCriteria, excludedTools, requiredTools);
    }

    public Goal withSuccessCriteria(String criteria) {
        return new Goal(id, parentId, status, description, dependencies,
                        allocatedBudget, criteria, excludedTools, requiredTools);
    }

    public Goal withExcludedTools(Set<String> excluded) {
        return new Goal(id, parentId, status, description, dependencies,
                        allocatedBudget, successCriteria, excluded, requiredTools);
    }

    public Goal withRequiredTools(Set<String> required) {
        return new Goal(id, parentId, status, description, dependencies,
                        allocatedBudget, successCriteria, excludedTools, required);
    }
}
