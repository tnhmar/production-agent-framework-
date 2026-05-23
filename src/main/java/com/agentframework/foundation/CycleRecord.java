package com.agentframework.foundation;

import java.util.List;

/**
 * Immutable record of one completed agent cycle, written to the execution trace.
 *
 * <h3>Partial-success error preservation</h3>
 * When the action layer runs a {@link ActionResult.ParallelToolCalls} fan-out,
 * some tools may succeed while others fail.  The result is an
 * {@link ActionResult.PartialSuccess} with a list of per-tool errors.
 * This list is now promoted to {@link #partialErrors} so that downstream
 * analytics pipelines can observe individual tool failures at cycle granularity
 * without needing to decode the {@link ActionResult} discriminant.
 *
 * <h3>Retry attribution</h3>
 * {@code retryCount} captures how many middleware-level retries occurred before
 * the action succeeded or permanently failed.  Zero means the first attempt
 * succeeded.
 *
 * <p>Use {@link #of} for all construction; the static factory selects the correct
 * source for {@code partialErrors} automatically.
 */
public record CycleRecord(
        int          cycleNumber,
        Observations observations,
        Decision     decision,
        ActionResult result,
        String       reviewSummary,
        int          retryCount,
        List<String> partialErrors) {

    /**
     * Compact canonical constructor — defensively copies the partial-errors list
     * so it cannot be mutated after construction.
     */
    public CycleRecord {
        partialErrors = (partialErrors == null)
            ? List.of()
            : List.copyOf(partialErrors);
    }

    /**
     * Factory for normal cycles where no retry context or partial errors are
     * available.  Extracts partial errors from
     * {@link ActionResult.PartialSuccess} automatically.
     */
    public static CycleRecord of(int cycleNumber, Observations obs,
                                  Decision decision, ActionResult result,
                                  String reviewSummary) {
        return of(cycleNumber, obs, decision, result, reviewSummary, 0);
    }

    /**
     * Factory with explicit retry count.  Partial errors are always
     * extracted from the result automatically — callers do not need to
     * pass them separately.
     */
    public static CycleRecord of(int cycleNumber, Observations obs,
                                  Decision decision, ActionResult result,
                                  String reviewSummary, int retryCount) {
        List<String> errors = (result instanceof ActionResult.PartialSuccess ps)
            ? ps.errors()   // individual per-tool error messages
            : List.of();
        return new CycleRecord(cycleNumber, obs, decision, result,
            reviewSummary, retryCount, errors);
    }

    /** Convenience: true if this cycle recorded at least one partial failure. */
    public boolean hasPartialErrors() {
        return !partialErrors.isEmpty();
    }
}
