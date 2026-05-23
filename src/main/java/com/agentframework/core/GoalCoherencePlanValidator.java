package com.agentframework.core;

import com.agentframework.foundation.*;
import java.util.Optional;

/**
 * Production {@link PlanValidator} that enforces goal coherence on every
 * decision cycle and after every world-changing action.
 *
 * <p>Replaces {@link PassThroughPlanValidator} as the recommended default.
 * Wire it into {@link AgentRuntime} at construction time:
 * <pre>{@code
 *   new AgentRuntime(new GoalCoherencePlanValidator(), events);
 * }</pre>
 *
 * <h3>Checks performed before dispatch ({@link #validate})</h3>
 * <ol>
 *   <li><b>Root-goal liveness</b> — rejects decisions if the root goal is
 *       already COMPLETED or FAILED, stopping runaway agents that keep acting
 *       after finishing.</li>
 *   <li><b>Escalation / clarification pass-through</b> — recovery decisions
 *       are never blocked by this validator.</li>
 *   <li><b>Tool exclusion tokens</b> — if the current goal's
 *       {@code successCriteria} contains a {@code !toolName} token, that tool
 *       is blocked and the cycle is flagged for plan correction.  This is a
 *       lightweight policy hook; deeper semantic matching lives in
 *       {@code SemanticActionValidator}.</li>
 * </ol>
 *
 * <h3>After-action check ({@link #validateAfterAction})</h3>
 * <p>After a world-changing action, verifies the root goal has not
 * inadvertently entered FAILED state; if so, flags the plan as stale.
 *
 * <p>Designed for composition: wrap with additional validators via
 * {@link CompositePlanValidator} for policy and domain-specific checks.
 */
public class GoalCoherencePlanValidator implements PlanValidator {

    @Override
    public ValidationResult validate(Decision decision, ExecutionContext ctx) {
        // Recovery paths must never be blocked
        if (decision instanceof Escalate || decision instanceof AskClarification)
            return new ValidationResult.Passed();

        Optional<Goal> root = ctx.goalStack().all().stream()
            .filter(g -> "root".equals(g.id()))
            .findFirst();

        if (root.isEmpty())
            return new ValidationResult.Failed(
                "No root goal on stack — cannot validate decision coherence");

        GoalStatus rootStatus = root.get().status();
        if (rootStatus == GoalStatus.COMPLETED)
            return new ValidationResult.Failed(
                "Root goal already COMPLETED; no further actions permitted");
        if (rootStatus == GoalStatus.FAILED)
            return new ValidationResult.Failed(
                "Root goal is FAILED; decision rejected until goal stack is reset");

        if (decision instanceof FinalAnswer)
            return new ValidationResult.Passed();

        if (decision instanceof ToolCall tc) {
            Goal current = ctx.goalStack().current().orElse(root.get());
            if (isExplicitlyExcluded(tc.toolName(), current))
                return new ValidationResult.NeedsCorrection(
                    "Tool '" + tc.toolName() + "' is excluded by goal '" +
                    current.id() + "' — choose an alternative approach");
        }

        return new ValidationResult.Passed();
    }

    @Override
    public ValidationResult validateAfterAction(ActionResult result, ExecutionContext ctx) {
        if (!(result instanceof ActionResult.Success))
            return new ValidationResult.Passed();

        boolean rootFailed = ctx.goalStack().all().stream()
            .filter(g -> "root".equals(g.id()))
            .anyMatch(g -> g.status() == GoalStatus.FAILED);

        return rootFailed
            ? new ValidationResult.NeedsCorrection(
                "Root goal entered FAILED state after action — plan correction required")
            : new ValidationResult.Passed();
    }

    /**
     * Returns {@code true} if the goal's success-criteria string contains
     * a {@code !toolName} exclusion token (case-insensitive).
     * Example criteria: "summarise the document !web_search !send_email"
     */
    private boolean isExplicitlyExcluded(String toolName, Goal goal) {
        if (goal.successCriteria() == null || goal.successCriteria().isBlank()) return false;
        return goal.successCriteria().toLowerCase()
                   .contains("!" + toolName.toLowerCase());
    }
}
