package com.agentframework.core;

import com.agentframework.foundation.*;
import java.util.List;
import java.util.Optional;

/**
 * Validates plan decisions against the active goal's coherence constraints.
 *
 * <h3>Terminal-decision policy</h3>
 * <ul>
 *   <li>{@link Escalate} and {@link AskClarification} — always pass. These
 *       represent the agent requesting human intervention; blocking them would
 *       trap the agent in an unrecoverable state.</li>
 *   <li>{@link FinalAnswer} — contextually validated. Delivering a final answer
 *       is coherent when the root goal is still active ({@code PENDING} or
 *       {@code ACTIVE}). A {@code FinalAnswer} issued after the root goal has
 *       already reached {@code COMPLETED} is a coherence violation (spurious
 *       duplicate answer) and is rejected with
 *       {@link ValidationResult.Failed}.</li>
 * </ul>
 *
 * <h3>Non-terminal decision policy</h3>
 * <ul>
 *   <li><b>excludedTools</b> — a tool in the active goal's excluded-tool set
 *       is blocked with {@link ValidationResult.NeedsCorrection}.</li>
 *   <li><b>requiredTools</b> — when non-empty, only whitelisted tools are
 *       permitted; any other tool call returns
 *       {@link ValidationResult.NeedsCorrection}.</li>
 * </ul>
 *
 * <h3>Empty-stack guard</h3>
 * Any decision (including {@link FinalAnswer}) issued before the state machine
 * has pushed a root goal is rejected with {@link ValidationResult.Failed}.
 */
public class GoalCoherencePlanValidator implements PlanValidator {

    @Override
    public ValidationResult validate(Decision d, ExecutionContext ctx) {

        // Unconditional bypass: Escalate and AskClarification must never be
        // blocked — doing so would prevent the agent from requesting help.
        if (d instanceof Escalate || d instanceof AskClarification) {
            return new ValidationResult.Passed();
        }

        // All other decisions (FinalAnswer included) require a root goal.
        List<Goal> allGoals = ctx.goalStack().all();
        if (allGoals.isEmpty()) {
            return new ValidationResult.Failed(
                "No root goal on the stack — context not initialised", List.of());
        }

        Optional<Goal> rootOpt = allGoals.stream()
            .filter(g -> "root".equals(g.id()))
            .findFirst();

        if (rootOpt.isEmpty()) {
            return new ValidationResult.Failed(
                "No goal with id 'root' found on the stack", List.of());
        }

        GoalStatus rootStatus = rootOpt.get().status();

        // FinalAnswer is coherent only while the root goal is still active.
        // Once the root is COMPLETED a second FinalAnswer is spurious.
        if (d instanceof FinalAnswer) {
            if (rootStatus == GoalStatus.COMPLETED) {
                return new ValidationResult.Failed(
                    "FinalAnswer rejected: root goal is already COMPLETED", List.of());
            }
            if (rootStatus == GoalStatus.FAILED) {
                return new ValidationResult.Failed(
                    "FinalAnswer rejected: root goal has FAILED", List.of());
            }
            return new ValidationResult.Passed();
        }

        // Non-terminal decisions on a finished root are incoherent.
        if (rootStatus == GoalStatus.COMPLETED) {
            return new ValidationResult.Failed(
                "Decision rejected: root goal is already COMPLETED", List.of());
        }
        if (rootStatus == GoalStatus.FAILED) {
            return new ValidationResult.Failed(
                "Decision rejected: root goal has FAILED", List.of());
        }

        // Tool-constraint checks (ToolCall only).
        if (d instanceof ToolCall tc) {
            Goal active = ctx.goalStack().current().orElse(rootOpt.get());
            String toolName = tc.toolName();

            if (active.excludedTools().contains(toolName)) {
                return new ValidationResult.NeedsCorrection(
                    "Tool '" + toolName + "' is excluded for goal '" + active.id() + "'",
                    null);
            }

            if (!active.requiredTools().isEmpty()
                    && !active.requiredTools().contains(toolName)) {
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
