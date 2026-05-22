package com.agentframework.multi;
import com.agentframework.core.*;
import com.agentframework.foundation.Task;
import java.util.*;
/**
 * Executes agents in sequence, feeding each agent's output as the next agent's instruction.
 */
public class PipelineOrchestrator implements AgentOrchestrator {
    public MultiAgentResult coordinate(Task task, List<AgentHandle> agents, ExecutionContext ctx) {
        if (agents.isEmpty()) throw new IllegalArgumentException("No agents");
        List<TaskTrace> traces  = new ArrayList<>();
        String          current = task.instruction();
        Object          lastOut = null;
        for (AgentHandle h : agents) {
            Task step = Task.builder().instruction(current)
                .maxCycles(task.maxCycles()).maxTokens(task.maxTokens())
                .maxWallClockTime(task.maxWallClockTime()).budgetLimit(task.budgetLimit())
                .build();
            switch (h) {
                case AgentHandle.Local l -> {
                    ExecutionResult r = l.runtime().execute(l.agent(), step);
                    lastOut = r.finalAnswer() != null ? r.finalAnswer() : "";
                    current = lastOut.toString();
                    traces.add(new TaskTrace(ctx.runId(), l.card().name(), r.finalState().name(), lastOut));
                }
                case AgentHandle.Remote r -> {
                    A2ATask t = r.client().sendTask(TaskSpec.of(current));
                    lastOut = t.result();
                    current = lastOut != null ? lastOut.toString() : "";
                    traces.add(new TaskTrace(t.taskId(), r.card().name(), t.state(), lastOut));
                }
            }
        }
        return new MultiAgentResult(lastOut, agents, ctx.runId(), traces);
    }
}
