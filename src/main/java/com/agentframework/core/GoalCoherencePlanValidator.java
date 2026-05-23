package com.agentframework.core;

import com.agentframework.foundation.*;
import java.util.List;
import java.util.Optional;

/**
 * Production {@link PlanValidator} enforcing goal coherence on every decision cycle.
 *
 * <ul>
 *   <li>Blocks when root goal is COMPLETED or FAILED.</li>
 *   <li>Escalation/clarification always pass through.</li>
 *   <li>{@code !toolName} tokens in {@code successCriteria} block that tool.</li>
 * </ul>
 */
public class GoalCoherencePlanValidator implements PlanValidator {

    @Override
    public ValidationResult validate(Decision decision, ExecutionContext ctx) {
        if (decision instanceof Escalate || decision instanceof AskClarification)
            return new ValidationResult.Passed();

        Optional<Goal> root = ctx.goalStack().all().stream()
            .filter(g -> "root".equals(g.id())).findFirst();

        if (root.isEmpty())
            return new ValidationResult.Failed(
                "No root goal on stack — cannot validate coherence", List.of());

        GoalStatus status = root.get().status();
        if (status == GoalStatus.COMPLETED)
            return new ValidationResult.Failed(
                "Root goal COMPLETED; no further actions permitted", List.of());
        if (status == GoalStatus.FAILED)
            return new ValidationResult.Failed(
                "Root goal FAILED; rejected until goal stack reset", List.of());

        if (decision instanceof FinalAnswer)
            return new ValidationResult.Passed();

        if (decision instanceof ToolCall tc) {
            Goal current = ctx.goalStack().current().orElse(root.get());
            if (isExcluded(tc.toolName(), current))
                return new ValidationResult.NeedsCorrection(
                    "Tool '" + tc.toolName() + "' excluded by goal '" +
                    current.id() + "' — choose an alternative", null);
        }
        return new ValidationResult.Passed();
    }

    @Override
    public ValidationResult validateAfterAction(ActionResult result, ExecutionContext ctx) {
        if (!(result instanceof ActionResult.Success)) return new ValidationResult.Passed();
        boolean rootFailed = ctx.goalStack().all().stream()
            .filter(g -> "root".equals(g.id()))
            .anyMatch(g -> g.status() == GoalStatus.FAILED);
        return rootFailed
            ? new ValidationResult.NeedsCorrection(
                "Root goal entered FAILED after action — plan correction required", null)
            : new ValidationResult.Passed();
    }

    private boolean isExcluded(String toolName, Goal goal) {
        String c = goal.successCriteria();
        return c != null && !c.isBlank()
            && c.toLowerCase().contains("!" + toolName.toLowerCase());
    }
}
