package com.agentframework.multi;
import com.agentframework.core.*;
import com.agentframework.foundation.Task;
import java.util.*;
public class SupervisorOrchestrator implements AgentOrchestrator {
    public MultiAgentResult coordinate(Task task, List<AgentHandle> agents, ExecutionContext ctx) {
        if (agents.isEmpty()) throw new IllegalArgumentException("No agents provided");
        List<TaskTrace> traces = new ArrayList<>();
        Object lastResult = null;
        for (AgentHandle h : agents) {
            switch (h) {
                case AgentHandle.Local local -> {
                    ExecutionResult r = local.runtime().execute(local.agent(), task);
                    lastResult = r.finalAnswer() != null ? r.finalAnswer() : r.finalState().name();
                    traces.add(new TaskTrace(ctx.runId(), local.card().name(),
                        r.finalState().name(), lastResult));
                }
                case AgentHandle.Remote remote -> {
                    A2ATask t = remote.client().sendTask(TaskSpec.of(task.instruction()));
                    lastResult = t.result();
                    traces.add(new TaskTrace(t.taskId(), remote.card().name(), t.state(), t.result()));
                }
            }
        }
        return new MultiAgentResult(lastResult, agents, ctx.runId(), traces);
    }
}
