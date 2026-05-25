package com.agentframework.core;

import com.agentframework.foundation.Decision;
import com.agentframework.foundation.Escalate;
import com.agentframework.foundation.FinalAnswer;
import com.agentframework.foundation.ParallelToolCalls;
import com.agentframework.foundation.TerminationReason;
import com.agentframework.foundation.ToolCall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Default {@link LivenessDetector} implementing the N1 (stagnation) and
 * N2 (stuck-state) detectors defined in Volume 1.
 *
 * <h3>N1 — Stagnation detector</h3>
 * After every cycle where the agent performed tool work or delivered a
 * final answer, the SHA-256 goal-state hash is compared to the pre-cycle
 * hash.  If they are equal for {@code maxStagnantCycles} consecutive cycles
 * the run is terminated with {@link TerminationReason.StagnationLimit}.
 *
 * <h3>N2 — Stuck-state detector</h3>
 * If the decision is neither a {@link ToolCall}, {@link ParallelToolCalls},
 * {@link FinalAnswer}, nor {@link Escalate} for {@code maxStuckCycles}
 * consecutive cycles the run is terminated with
 * {@link TerminationReason.Escalated}.
 *
 * <p>Both thresholds are constructor-configurable so tests and integrations can
 * use lower values without modifying production constants.
 */
public class DefaultLivenessDetector implements LivenessDetector {

    private final int maxStagnantCycles;
    private final int maxStuckCycles;

    /** Production defaults: stagnation after 3 cycles, stuck after 2. */
    public DefaultLivenessDetector() {
        this(3, 2);
    }

    public DefaultLivenessDetector(int maxStagnantCycles, int maxStuckCycles) {
        if (maxStagnantCycles < 1) throw new IllegalArgumentException("maxStagnantCycles must be >= 1");
        if (maxStuckCycles < 1)    throw new IllegalArgumentException("maxStuckCycles must be >= 1");
        this.maxStagnantCycles = maxStagnantCycles;
        this.maxStuckCycles    = maxStuckCycles;
    }

    // ── N1: Stagnation ─────────────────────────────────────────────

    @Override
    public Optional<TerminationReason> checkStagnation(
            String preHash, String postHash, Decision decision, ExecutionContext ctx) {
        if (!isSubstantiveDecision(decision)) {
            return Optional.empty();
        }
        if (preHash.equals(postHash)) {
            ctx.incrementStagnantCycles();
            if (ctx.stagnantCycles() >= maxStagnantCycles) {
                return Optional.of(new TerminationReason.StagnationLimit(
                    ctx.stagnantCycles(), postHash));
            }
        } else {
            ctx.resetStagnantCycles();
        }
        return Optional.empty();
    }

    // ── N2: Stuck-state ─────────────────────────────────────────────

    @Override
    public Optional<TerminationReason> checkStuck(Decision decision, ExecutionContext ctx) {
        if (isSubstantiveDecision(decision)) {
            ctx.resetStuckCycles();
            return Optional.empty();
        }
        ctx.incrementStuckCycles();
        if (ctx.stuckCycles() >= maxStuckCycles) {
            return Optional.of(new TerminationReason.Escalated(
                "Agent stuck: " + ctx.stuckCycles()
                + " consecutive cycles with no tool call and no terminal decision. "
                + "Last decision type: " + decision.getClass().getSimpleName()));
        }
        return Optional.empty();
    }

    // ── Goal-state hashing (static — testable independently) ─────────────

    /**
     * Computes a SHA-256-based hash over the id and status of every goal
     * on the stack.  Returns the full 64-character hex digest.
     */
    public static String hashGoalState(List<Goal> goals) {
        String canonical = goals.stream()
            .map(g -> g.id() + "=" + g.status().name())
            .collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Substantive decisions are those that constitute forward progress. */
    private static boolean isSubstantiveDecision(Decision d) {
        return d instanceof ToolCall
            || d instanceof ParallelToolCalls
            || d instanceof FinalAnswer
            || d instanceof Escalate;
    }
}
