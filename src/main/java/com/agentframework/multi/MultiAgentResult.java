package com.agentframework.multi;
import java.util.List;

/**
 * Result of a multi-agent coordination run.
 *
 * <p>{@code allResults} contains one entry per contributing agent in execution
 * order — the supervisor fan-out pattern populates all entries, the pipeline
 * pattern populates only the final entry (last stage output).
 */
public record MultiAgentResult(
        List<Object>       allResults,
        List<AgentHandle>  contributors,
        String             correlationId,
        List<TaskTrace>    subTraces) {

    /** Convenience accessor — returns the last element of {@code allResults},
     *  equivalent to the old {@code finalResult} field. */
    public Object finalResult() {
        return allResults.isEmpty() ? null : allResults.get(allResults.size() - 1);
    }
}
