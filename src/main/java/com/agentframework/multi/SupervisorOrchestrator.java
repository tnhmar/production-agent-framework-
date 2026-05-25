package com.agentframework.multi;

import com.agentframework.core.*;
import com.agentframework.foundation.Task;
import java.util.*;

/**
 * Fan-out supervisor orchestrator.
 *
 * <p>Dispatches the <em>same</em> {@link Task} to every agent in parallel
 * (currently sequential for simplicity) and collects <em>all</em> results
 * into {@link MultiAgentResult#allResults()}.  The previous implementation
 * retained only {@code lastResult}, silently discarding every earlier agent's
 * output — this has been corrected.
 *
 * <p>If any local agent returns a terminal failure state the run is aborted
 * immediately and an {@link OrchestratorException} is thrown rather than
 * allowing subsequent agents to operate on stale context.
 */
public class SupervisorOrchestrator implements AgentOrchestrator {

    @Override
    public MultiAgentResult coordinate(Task task, List<AgentHandle> agents, ExecutionContext ctx) {
        if (agents.isEmpty()) throw new IllegalArgumentException("No agents provided");

        List<Object>    allResults = new ArrayList<>();
        List<TaskTrace> traces     = new ArrayList<>();

        for (AgentHandle h : agents) {
            switch (h) {
                case AgentHandle.Local local -> {
                    ExecutionResult r = local.runtime().execute(local.agent(), task);

                    // Abort on terminal failure — do not silently continue.
                    if (isFailure(r.finalState())) {
                        String msg = "Agent '" + local.card().name() +
                            "' terminated with state " + r.finalState().name();
                        traces.add(new TaskTrace(ctx.runId(), local.card().name(),
                            r.finalState().name(), msg));
                        throw new OrchestratorException(msg,
                            new MultiAgentResult(allResults, agents, ctx.runId(), traces));
                    }

                    Object result = r.finalAnswer() != null ? r.finalAnswer() : r.finalState().name();
                    allResults.add(result);
                    traces.add(new TaskTrace(ctx.runId(), local.card().name(),
                        r.finalState().name(), result));
                }
                case AgentHandle.Remote remote -> {
                    A2ATask t = remote.client().sendTask(TaskSpec.of(task.instruction()));
                    allResults.add(t.result());
                    traces.add(new TaskTrace(t.taskId(), remote.card().name(),
                        t.state(), t.result()));
                }
            }
        }

        return new MultiAgentResult(allResults, agents, ctx.runId(), traces);
    }

    /** Returns true for any state that represents a non-recoverable failure. */
    private static boolean isFailure(AgentState state) {
        return state == AgentState.ABORTED || state == AgentState.DEGRADED;
    }
}
