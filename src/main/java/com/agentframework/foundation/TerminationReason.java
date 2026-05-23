package com.agentframework.foundation;

/**
 * Sealed hierarchy that describes why an agent run was terminated.
 *
 * <p>Each subtype is semantically distinct so that event consumers, dashboards,
 * and analytics pipelines can differentiate categories without parsing
 * free-text message strings:
 * <ul>
 *   <li>{@link GoalCompleted}    — success path; all root goals were achieved.</li>
 *   <li>{@link Escalated}        — the agent explicitly escalated to a human or
 *                                  supervisory agent via an {@code Escalate} decision.</li>
 *   <li>{@link ResourceLimit}    — a quantitative budget (cycles, tokens, wall-clock,
 *                                  cost) was exhausted before the goal was reached.</li>
 *   <li>{@link FailureEscalation}— too many consecutive tool failures; the agent
 *                                  cannot make progress and gives up.</li>
 *   <li>{@link SecurityViolation}— a HOSTILE-tainted payload triggered a defensive
 *                                  abort; carries the truncated payload for audit.</li>
 *   <li>{@link StagnationLimit}  — the goal-state hash was unchanged for
 *                                  {@code cyclesStuck} consecutive cycles, indicating
 *                                  the agent is in an unproductive loop.</li>
 *   <li>{@link PlanIncoherent}   — the plan validator rejected a decision because it
 *                                  violated a goal constraint; revision budget exhausted.</li>
 * </ul>
 */
public sealed interface TerminationReason
    permits TerminationReason.GoalCompleted,
            TerminationReason.Escalated,
            TerminationReason.ResourceLimit,
            TerminationReason.FailureEscalation,
            TerminationReason.SecurityViolation,
            TerminationReason.StagnationLimit,
            TerminationReason.PlanIncoherent {

    record GoalCompleted()
        implements TerminationReason {}

    record Escalated(String reason)
        implements TerminationReason {}

    record ResourceLimit(String detail)
        implements TerminationReason {}

    record FailureEscalation(String detail)
        implements TerminationReason {}

    /**
     * Terminated because a tool result or working-memory entry was classified
     * {@link TaintLabel#HOSTILE}.
     *
     * @param truncatedPayload the first 200 characters of the hostile content,
     *                         for audit logging. Never null; may be empty.
     */
    record SecurityViolation(String truncatedPayload)
        implements TerminationReason {}

    /**
     * Terminated because the agent's goal-state hash was unchanged for
     * too many consecutive cycles.
     *
     * @param cyclesStuck number of consecutive stagnant cycles at termination
     * @param goalHash    SHA-256 of the frozen goal state, for replay correlation
     */
    record StagnationLimit(int cyclesStuck, String goalHash)
        implements TerminationReason {}

    /**
     * Terminated because the plan validator rejected the last possible revision
     * of the current plan.
     *
     * @param violatedConstraint human-readable description of the violated rule
     */
    record PlanIncoherent(String violatedConstraint)
        implements TerminationReason {}
}
