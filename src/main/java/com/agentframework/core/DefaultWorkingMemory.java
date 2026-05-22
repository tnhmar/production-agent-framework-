package com.agentframework.core;
import com.agentframework.foundation.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
public class DefaultWorkingMemory implements WorkingMemory {
    private final List<WorkingMemoryEntry> entries   = Collections.synchronizedList(new ArrayList<>());
    private final Set<String>              processed = ConcurrentHashMap.newKeySet();

    public void add(WorkingMemoryEntry e) { entries.add(e); }

    public List<WorkingMemoryEntry> getAll()         { return new ArrayList<>(entries); }
    public List<WorkingMemoryEntry> getByOrigin(Origin o) {
        return entries.stream().filter(e -> e.origin()==o).collect(Collectors.toList());
    }
    public List<WorkingMemoryEntry> getUnprocessed() {
        return entries.stream().filter(e -> !processed.contains(e.id())).collect(Collectors.toList());
    }
    public void markProcessed(String id)  { processed.add(id); }
    public boolean isProcessed(String id) { return processed.contains(id); }
    public void evictOldest(int n) {
        synchronized(entries) {
            entries.sort(Comparator.comparing(WorkingMemoryEntry::timestamp));
            int del = Math.min(n, entries.size());
            entries.subList(0, del).clear();
        }
    }
    public void evictLowestRelevance(int n) {
        synchronized(entries) {
            entries.sort(Comparator.comparingDouble(WorkingMemoryEntry::relevanceScore));
            int del = Math.min(n, entries.size());
            entries.subList(0, del).clear();
        }
    }
    public void compress(List<String> ids, String summary) {
        ids.forEach(id -> entries.removeIf(e -> e.id().equals(id)));
        add(new WorkingMemoryEntry(UUID.randomUUID().toString(), summary,
            WorkingMemoryTier.COMPRESSED, Origin.SYSTEM, 0.5, Instant.now(), TaintLabel.CLEAN));
    }
    public int estimatedTokenCount() {
        return entries.stream().mapToInt(e -> e.content().length()/4).sum();
    }
    public int  size()  { return entries.size(); }
    public void clear() { entries.clear(); processed.clear(); }
}
