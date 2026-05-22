package com.agentframework.observability;
import java.time.Instant;
import java.util.Map;
public record AgentEvent(String runId, String tenantId, EventType type,
        Instant timestamp, Map<String,Object> attributes) {
    public enum EventType {
        RUN_STARTED, RUN_COMPLETED, RUN_ABORTED,
        CYCLE_STARTED, CYCLE_COMPLETED,
        TOOL_CALLED, TOOL_SUCCEEDED, TOOL_FAILED,
        PLAN_STALE, BELIEF_CONFLICT, HITL_REQUESTED, HITL_RESOLVED,
        MEMORY_WRITTEN, MEMORY_RETRIEVED,
        CONTEXT_EVICTED, RESOURCE_LIMIT_HIT
    }
}
