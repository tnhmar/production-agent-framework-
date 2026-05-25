package com.agentframework.multi;

/**
 * Thrown by an {@link AgentOrchestrator} when one of the coordinated agents
 * reaches a terminal failure state and the run cannot continue safely.
 *
 * <p>The partial {@link MultiAgentResult} accumulated up to the point of
 * failure is attached so callers can inspect which agents succeeded and
 * which traces are available.
 */
public class OrchestratorException extends RuntimeException {

    private final MultiAgentResult partialResult;

    public OrchestratorException(String message, MultiAgentResult partialResult) {
        super(message);
        this.partialResult = partialResult;
    }

    public OrchestratorException(String message, MultiAgentResult partialResult, Throwable cause) {
        super(message, cause);
        this.partialResult = partialResult;
    }

    /** The partial result collected before the failure. Never {@code null}. */
    public MultiAgentResult partialResult() {
        return partialResult;
    }
}
