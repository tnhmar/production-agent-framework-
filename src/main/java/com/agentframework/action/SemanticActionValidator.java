package com.agentframework.action;

import com.agentframework.core.ExecutionContext;
import com.agentframework.core.GoalStatus;
import com.agentframework.foundation.*;

/**
 * Semantic validation layer (layer 2 of 4 in the validation stack).
 *
 * <p>Checks that the proposed tool call is semantically coherent with the
 * current goal-stack state.  Specifically it blocks a tool call when:
 * <ul>
 *   <li>The currently active goal has already been marked COMPLETED or FAILED —
 *       issuing more tool calls for a closed goal indicates a planning anomaly.</li>
 *   <li>The tool's declared side-effect class is IRREVERSIBLE and the current
 *       goal is still only PENDING (no evidence-gathering has happened yet) —
 *       an irreversible write before any retrieval step is a strong signal of
 *       goal drift or a prompt-injection attack.</li>
 * </ul>
 *
 * <p>All other cases pass through to the next validator in the chain.
 */
public class SemanticActionValidator implements ActionValidator {

    public ValidationVerdict validate(ToolCall call, ToolContract contract, ExecutionContext ctx) {

        return ctx.goalStack().current().map(goal -> {

            // A closed goal should not accept new tool calls
            if (goal.status() == GoalStatus.COMPLETED)
                return ValidationVerdict.failed(
                    "Active goal is already COMPLETED; tool call \"" + call.toolName()
                    + "\" is incoherent with current goal state");

            if (goal.status() == GoalStatus.FAILED)
                return ValidationVerdict.failed(
                    "Active goal is in FAILED state; tool call \"" + call.toolName()
                    + "\" requires goal recovery before proceeding");

            // Irreversible write before any cycle-level evidence gathering
            if (contract != null
                    && contract.sideEffect() == ToolContract.SideEffectClass.IRREVERSIBLE
                    && goal.status() == GoalStatus.PENDING
                    && ctx.cycleCount() == 0)
                return ValidationVerdict.failed(
                    "Irreversible tool call \"" + call.toolName()
                    + "\" attempted on a PENDING goal before any evidence retrieval");

            return ValidationVerdict.ok();

        }).orElse(ValidationVerdict.ok()); // no active goal → let other validators decide
    }
}
