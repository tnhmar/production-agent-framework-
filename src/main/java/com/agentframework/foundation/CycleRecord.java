package com.agentframework.foundation;

/**
 * Immutable record of one completed agent cycle, written to the execution trace.
 *
 * <p>{@code retryCount} captures how many middleware-level retries were made
 * before the action succeeded or permanently failed. Zero means the first attempt
 * succeeded. This field is required by the Volume 1 compliance audit trail:
 * a cycle that retried three times must be distinguishable from one that did not.
 *
 * <p>Use the static factory {@link #of} for calls that do not go through the
 * retry middleware (plan-level decisions, validation failures, etc.).
 */
public record CycleRecord(
        int          cycleNumber,
        Observations observations,
        Decision     decision,
        ActionResult result,
        String       reviewSummary,
        int          retryCount) {

    /**
     * Factory for cycles where no retry context is available — retryCount defaults to 0.
     * Keeps existing call-sites in the state machine backward-compatible.
     */
    public static CycleRecord of(int cycleNumber, Observations obs,
                                  Decision decision, ActionResult result,
                                  String reviewSummary) {
        return new CycleRecord(cycleNumber, obs, decision, result, reviewSummary, 0);
    }
}
