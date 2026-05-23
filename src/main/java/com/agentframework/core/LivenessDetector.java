package com.agentframework.core;

import com.agentframework.foundation.TerminationReason;

import java.util.Optional;

/**
 * Strategy that evaluates agent liveness after each cycle.
 *
 * <p>Two distinct liveness checks are defined by Volume 1:
 * <ul>
 *   <li><b>Stagnation (N1)</b>: the goal-state hash is identical for
 *       {@code maxStagnantCycles} consecutive cycles, indicating the agent is
 *       looping without advancing toward its goal.</li>
 *   <li><b>Stuck-state (N2)</b>: the agent produces neither a tool call, a
 *       parallel call, a final answer, nor an escalation for
 *       {@code maxStuckCycles} consecutive cycles, indicating the model is
 *       generating empty or unrecognised output.</li>
 * </ul>
 *
 * <p>Returning an empty {@link Optional} means the agent is still live.
 * Returning a {@link TerminationReason} means the run should be terminated
 * with that reason.
 */
public interface LivenessDetector {

    /**
     * Evaluates stagnation after a completed cycle.
     *
     * @param preHash  goal-state hash computed before the model call
     * @param postHash goal-state hash computed after action and review
     * @param decision the decision produced by the reasoning engine
     * @param ctx      the current execution context
     * @return a termination reason if stagnation is confirmed, empty otherwise
     */
    Optional<TerminationReason> checkStagnation(
        String preHash, String postHash, Decision decision, ExecutionContext ctx);

    /**
     * Evaluates stuck-state after a reasoning cycle.
     *
     * @param decision the decision produced by the reasoning engine
     * @param ctx      the current execution context
     * @return a termination reason if stuck-state is confirmed, empty otherwise
     */
    Optional<TerminationReason> checkStuck(Decision decision, ExecutionContext ctx);
}
