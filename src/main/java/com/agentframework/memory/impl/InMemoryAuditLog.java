package com.agentframework.memory.impl;
import com.agentframework.core.RequestContext;
import com.agentframework.memory.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
public class InMemoryAuditLog implements MemoryAuditLog {
    private final CopyOnWriteArrayList<MemoryAuditEntry> log = new CopyOnWriteArrayList<>();

    public void record(MemoryAuditEntry e, RequestContext ctx) { log.add(e); }
    public List<MemoryAuditEntry> historyOf(String recordId) {
        return log.stream().filter(e -> e.recordId().equals(recordId)).collect(Collectors.toList());
    }
    public List<MemoryAuditEntry> writesBy(String agentId, Duration window) {
        Instant cutoff = Instant.now().minus(window);
        return log.stream().filter(e -> e.agentId().equals(agentId)
                                     && e.timestamp().isAfter(cutoff)).collect(Collectors.toList());
    }
    public List<MemoryAuditEntry> writesFromUntrustedSources() {
        return log.stream().filter(e -> e.source().startsWith("external:")).collect(Collectors.toList());
    }
    public List<MemoryAuditEntry> allRecordsForUser(String userId) {
        return log.stream().filter(e -> e.userId().equals(userId)).collect(Collectors.toList());
    }
    public int size() { return log.size(); }
    public List<MemoryAuditEntry> all() { return new ArrayList<>(log); }
}
