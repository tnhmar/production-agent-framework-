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
 */
public class GoalCoherencePlanValidator implements PlanValidator {

    @Override
    public ValidationResult validate(Decision d, ExecutionContext ctx) {
        // Terminal decisions always pass—blocking them would trap the agent
        if (d instanceof Escalate || d instanceof AskClarification || d instanceof FinalAnswer) {
            return new ValidationResult.Passed();
        }

        Optional<Goal> rootOpt = ctx.goalStack().all().stream()
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

            // Typed whitelist check — only applies when the required set is non-empty
            if (!active.requiredTools().isEmpty() && !active.requiredTools().contains(toolName)) {
                return new ValidationResult.NeedsCorrection(
                    "Tool '" + toolName + "' is not in the required-tool set for goal '"
                    + active.id() + "'. Permitted tools: " + active.requiredTools(), null);
            }
        }

        return new ValidationResult.Passed();
    }

    @Override
    public ValidationResult validateAfterAction(ActionResult r, ExecutionContext ctx) {
        if (!(r instanceof ActionResult.Success)) return new ValidationResult.Passed();
        boolean rootFailed = ctx.goalStack().all().stream()
            .filter(g -> "root".equals(g.id()))
            .anyMatch(g -> g.status() == GoalStatus.FAILED);
        return rootFailed
            ? new ValidationResult.NeedsCorrection("Root goal entered FAILED state after action", null)
            : new ValidationResult.Passed();
    }
}
