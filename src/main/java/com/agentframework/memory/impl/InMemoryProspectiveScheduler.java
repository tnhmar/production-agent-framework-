package com.agentframework.memory.impl;
import com.agentframework.core.ExecutionContext;
import com.agentframework.memory.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
public class InMemoryProspectiveScheduler implements ProspectiveScheduler {
    private final CopyOnWriteArrayList<ProspectiveRecord> records = new CopyOnWriteArrayList<>();

    public void schedule(ProspectiveRecord r) { records.add(r); }
    public void cancel(String id) { records.removeIf(r -> r.id().equals(id)); }
    public List<ProspectiveRecord> pendingForUser(String userId) {
        return records.stream().filter(r -> r.userId().equals(userId) && !r.fired())
               .collect(Collectors.toList());
    }
    public List<ProspectiveRecord> getDue(Instant now, Duration idleTime, ExecutionContext ctx) {
        return records.stream().filter(r -> !r.fired() && isDue(r.trigger(), now, idleTime))
               .collect(Collectors.toList());
    }
    private boolean isDue(Trigger t, Instant now, Duration idleTime) {
        return switch (t) {
            case Trigger.TimeBased(var target)    -> now.isAfter(target);
            case Trigger.IdleBased(var dur)       -> idleTime != null && idleTime.compareTo(dur) >= 0;
            case Trigger.ConditionBased(var cond) -> false;
            case Trigger.SessionStart()           -> true;
        };
    }
}
