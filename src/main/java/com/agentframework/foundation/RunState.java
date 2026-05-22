package com.agentframework.foundation;
/** Combined state machine: execution-phase granularity (from zip) + session-lifecycle states (Sol3). */
public enum RunState {
    INITIALIZED, VALIDATING, PLANNING, MODEL_CALL, TOOL_EXECUTION,
    MEMORY_UPDATE, RESPONDING, AWAITING_RESULT,
    DEGRADED, SUSPENDED_HITL, WAITING_FOR_JOB,
    COMPLETED, ABORTED, TERMINATED;
    public boolean isTerminal() {
        return this == COMPLETED || this == ABORTED || this == TERMINATED;
    }
    public boolean isLive() { return !isTerminal(); }
}
