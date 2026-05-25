package com.agentframework.multi;

/**
 * Thrown by {@link PipelineOrchestrator} when a pipeline stage produces
 * blank output or reaches a terminal failure state.
 *
 * <p>The partial {@link MultiAgentResult} accumulated up to the failing stage
 * is attached for diagnostic purposes.
 */
public class PipelineAbortException extends RuntimeException {

    private final MultiAgentResult partialResult;

    public PipelineAbortException(String message, MultiAgentResult partialResult) {
        super(message);
        this.partialResult = partialResult;
    }

    public PipelineAbortException(String message, MultiAgentResult partialResult, Throwable cause) {
        super(message, cause);
        this.partialResult = partialResult;
    }

    /** The partial result collected before the abort. Never {@code null}. */
    public MultiAgentResult partialResult() {
        return partialResult;
    }
}
