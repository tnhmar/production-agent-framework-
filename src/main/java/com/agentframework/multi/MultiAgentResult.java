package com.agentframework.multi;
import java.util.List;
public record MultiAgentResult(Object finalResult, List<AgentHandle> contributors,
        String correlationId, List<TaskTrace> subTraces) {}
