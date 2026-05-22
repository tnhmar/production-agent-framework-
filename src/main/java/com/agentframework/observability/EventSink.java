package com.agentframework.observability;
public interface EventSink {
    void emit(AgentEvent event);
    static EventSink noop() { return e -> {}; }
}
