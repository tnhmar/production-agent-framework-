package com.agentframework.multi;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.Task;
import java.util.List;
public interface AgentOrchestrator {
    MultiAgentResult coordinate(Task task, List<AgentHandle> agents, ExecutionContext ctx);
}
