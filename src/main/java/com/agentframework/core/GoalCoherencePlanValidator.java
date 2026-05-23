package com.agentframework.core;

import com.agentframework.foundation.*;
import java.util.List;
import java.util.Optional;

/**
 * Validates plan decisions against the active goal's coherence constraints.
 *
 * <p>This validator enforces two classes of typed tool constraints:
 * <ul>
 *   <li><b>excludedTools</b> — a tool that appears in the current goal's
 *       excluded-tool set is blocked with a {@link ValidationResult.NeedsCorrection}
 *       response, prompting the agent to choose an alternative tool.</li>
 *   <li><b>requiredTools</b> — when the set is non-empty, only the whitelisted
 *       tools are permitted; any other tool call is rejected.</li>
 * </ul>
 *
 * <p>Terminal decisions ({@link Escalate}, {@link AskClarification}, {@link FinalAnswer})
 * are always passed through — the agent must always be able to terminate gracefully.
 *
 * <p>Post-action re-validation checks that a world-changing action has not driven
 * the root goal into FAILED status.
 *
 * <p><b>Empty-stack guard</b>: if {@code goalStack().all()} is empty (i.e. the
 * context has not been initialised by the state machine's INITIALIZED step) any
 * non-terminal decision is rejected with {@link ValidationResult.Failed}.  This
 * makes the validator safe to use in unit tests that build a bare
 * {@link DefaultExecutionContext} without running it through the state machine.
 */
public class GoalCoherencePlanValidator implements PlanValidator {

    @Override
    public ValidationResult validate(Decision d, ExecutionContext ctx) {
        // Terminal decisions always pass—blocking them would trap the agent
        if (d instanceof Escalate || d instanceof AskClarification || d instanceof FinalAnswer) {
            return new ValidationResult.Passed();
        }

        // Explicit empty-stack guard: a non-terminal decision issued before the
        // state machine has pushed a root goal is always a coherence failure.
        List<Goal> allGoals = ctx.goalStack().all();
        if (allGoals.isEmpty()) {
            return new ValidationResult.Failed("No root goal on the stack", List.of());
        }

        Optional<Goal> rootOpt = allGoals.stream()
            .filter(g -> "root".equals(g.id()))
            .findFirst();

        if (rootOpt.isEmpty()) {
            return new ValidationResult.Failed("No root goal on the stack", List.of());
        }

        GoalStatus rootStatus = rootOpt.get().status();
        if (rootStatus == GoalStatus.COMPLETED) {
            return new ValidationResult.Failed("Root goal is already COMPLETED", List.of());
        }
        if (rootStatus == GoalStatus.FAILED) {
            return new ValidationResult.Failed("Root goal has FAILED", List.of());
        }

        if (d instanceof ToolCall tc) {
            Goal active = ctx.goalStack().current().orElse(rootOpt.get());
            String toolName = tc.toolName();

            // Typed exclusion check — O(1) set lookup, no string parsing
            if (active.excludedTools().contains(toolName)) {
                return new ValidationResult.NeedsCorrection(
                    "Tool '" + toolName + "' is excluded for goal '" + active.id() + "'", null);
            }

            // Typed whitelist check — only enforced when set is non-empty
            if (!active.requiredTools().isEmpty() && !active.requiredTools().contains(toolName)) {
                return new ValidationResult.NeedsCorrection(
                    "Tool '" + toolName + "' is not in the required-tool whitelist for goal '"
                    + active.id() + "'", null);
            }
        }

        return new ValidationResult.Passed();
    }

    @Override
    public ValidationResult validateAfterAction(ActionResult result, ExecutionContext ctx) {
        Optional<Goal> rootOpt = ctx.goalStack().all().stream()
            .filter(g -> "root".equals(g.id()))
            .findFirst();
        if (rootOpt.isPresent() && rootOpt.get().status() == GoalStatus.FAILED) {
            return new ValidationResult.NeedsCorrection(
                "Root goal has transitioned to FAILED after action", null);
        }
        return new ValidationResult.Passed();
    }
}
