package com.agentframework.multi;

import com.agentframework.core.*;
import com.agentframework.foundation.RunState;
import com.agentframework.foundation.Task;
import java.util.*;

/**
 * Executes agents in sequence, feeding each agent's output as the next
 * agent's instruction.
 *
 * <p>If a step returns a null or blank {@code finalAnswer} after a successful
 * completion state, a {@link PipelineAbortException} is thrown rather than
 * forwarding a blank instruction to the next agent — a blank instruction is
 * indistinguishable from a silent failure and would cause the downstream
 * agent to operate with no meaningful input.
 *
 * <h3>Failure detection (C1 fix)</h3>
 * <p>{@link #isFailure(RunState)} now includes {@link RunState#TERMINATED} in
 * addition to {@code ABORTED} and {@code DEGRADED}, matching
 * {@code SupervisorOrchestrator.isFailure()}.  Previously a sub-agent that
 * exited in {@code TERMINATED} state (hostile taint block, resource exhaustion,
 * consecutive failures, plan incoherence) was not detected as a failure; the
 * pipeline then attempted to read {@code r.finalAnswer()}, received {@code null},
 * and threw {@code PipelineAbortException("BLANK_OUTPUT")} — hiding the real
 * termination reason in logs and dashboards.
 */
public class PipelineOrchestrator implements AgentOrchestrator {

    @Override
    public MultiAgentResult coordinate(Task task, List<AgentHandle> agents, ExecutionContext ctx) {
        if (agents.isEmpty()) throw new IllegalArgumentException("No agents");

        List<Object> allResults = new ArrayList<>();
        List<TaskTrace> traces  = new ArrayList<>();
        String current          = task.instruction();
        Object lastOut          = null;

        for (AgentHandle h : agents) {
            Task step = Task.builder()
                .instruction(current)
                .maxCycles(task.maxCycles())
                .maxTokens(task.maxTokens())
                .maxWallClockTime(task.maxWallClockTime())
                .budgetLimit(task.budgetLimit())
                .build();

            switch (h) {
                case AgentHandle.Local l -> {
                    ExecutionResult r = l.runtime().execute(l.agent(), step);
                    String agentName  = l.card().name();

                    if (isFailure(r.finalState())) {
                        traces.add(new TaskTrace(ctx.runId(), agentName,
                            r.finalState().name(), "FAILED"));
                        throw new PipelineAbortException(
                            "Pipeline aborted: agent '" + agentName +
                            "' terminated with " + r.finalState().name(),
                            new MultiAgentResult(allResults, agents, ctx.runId(), traces));
                    }

                    // Guard against a blank output being forwarded to the next stage.
                    String answer = r.finalAnswer() != null ? r.finalAnswer().toString().strip() : "";
                    if (answer.isBlank()) {
                        traces.add(new TaskTrace(ctx.runId(), agentName,
                            r.finalState().name(), "BLANK_OUTPUT"));
                        throw new PipelineAbortException(
                            "Pipeline aborted: agent '" + agentName +
                            "' produced blank output after " + r.finalState().name(),
                            new MultiAgentResult(allResults, agents, ctx.runId(), traces));
                    }

                    lastOut = answer;
                    current = answer;
                    allResults.add(lastOut);
                    traces.add(new TaskTrace(ctx.runId(), agentName,
                        r.finalState().name(), lastOut));
                }
                case AgentHandle.Remote r -> {
                    A2ATask t = r.client().sendTask(TaskSpec.of(current));
                    lastOut = t.result();
                    current = lastOut != null ? lastOut.toString() : "";
                    allResults.add(lastOut);
                    traces.add(new TaskTrace(t.taskId(), r.card().name(),
                        t.state(), lastOut));
                }
            }
        }

        return new MultiAgentResult(allResults, agents, ctx.runId(), traces);
    }

    /**
     * Returns {@code true} when the sub-agent run state represents a failure
     * that should abort the pipeline.
     *
     * <p>Includes {@link RunState#TERMINATED} (C1 fix) so that sub-agents
     * exiting via hostile-taint blocks, resource exhaustion, consecutive
     * failures, or plan incoherence are correctly surfaced as pipeline failures
     * rather than being masked as blank-output errors.
     */
    private static boolean isFailure(RunState state) {
        return state == RunState.ABORTED
            || state == RunState.DEGRADED
            || state == RunState.TERMINATED;  // C1 fix
    }
}
