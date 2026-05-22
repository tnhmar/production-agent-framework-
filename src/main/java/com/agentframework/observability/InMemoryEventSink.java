package com.agentframework.observability;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
public class InMemoryEventSink implements EventSink {
    private final CopyOnWriteArrayList<AgentEvent> events = new CopyOnWriteArrayList<>();
    public void emit(AgentEvent e) { events.add(e); }
    public List<AgentEvent> all() { return new ArrayList<>(events); }
    public List<AgentEvent> of(AgentEvent.EventType type) {
        return events.stream().filter(e -> e.type()==type).collect(Collectors.toList());
    }
    public int count(AgentEvent.EventType type) { return of(type).size(); }
    public int total() { return events.size(); }
    public void clear() { events.clear(); }
}
