package com.agentframework.core;

import com.agentframework.foundation.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Default in-memory working memory implementation.
 *
 * <p>M2 fix: {@link #evictLowestRelevance(int)} is now tier-aware.
 * Eviction priority: ARCHIVED → COMPRESSED → BACKGROUND → ACTIVE.
 * Within the same tier, entries are sorted by ascending relevance score (lowest evicted first).
 *
 * <p>IC4 fix: evicted entries are removed from the processed set to prevent
 * ghost references in {@link #getUnprocessed()}.
 */
public class DefaultWorkingMemory implements WorkingMemory {
    private final List<WorkingMemoryEntry> entries   = Collections.synchronizedList(new ArrayList<>());
    private final Set<String>              processed = ConcurrentHashMap.newKeySet();

    public void add(WorkingMemoryEntry e) { entries.add(e); }

    public List<WorkingMemoryEntry> getAll()              { return new ArrayList<>(entries); }

    public List<WorkingMemoryEntry> getByOrigin(Origin o) {
        return entries.stream().filter(e -> e.origin() == o).collect(Collectors.toList());
    }

    public List<WorkingMemoryEntry> getUnprocessed() {
        return entries.stream()
            .filter(e -> !processed.contains(e.id()))
            .collect(Collectors.toList());
    }

    public void    markProcessed(String id)  { processed.add(id); }
    public boolean isProcessed(String id)    { return processed.contains(id); }

    public void evictOldest(int n) {
        synchronized (entries) {
            entries.sort(Comparator.comparing(WorkingMemoryEntry::timestamp));
            evictFirst(n);
        }
    }

    /**
     * M2 fix: tier-aware eviction.
     * Sort order: tier priority ascending (ARCHIVED=0 ... ACTIVE=3), then relevance ascending.
     * Lowest-priority, lowest-relevance entries are removed first.
     */
    public void evictLowestRelevance(int n) {
        synchronized (entries) {
            entries.sort(
                Comparator.comparingInt((WorkingMemoryEntry e) -> tierPriority(e.tier()))
                          .thenComparingDouble(WorkingMemoryEntry::relevanceScore)
            );
            evictFirst(n);
        }
    }

    /** Removes the first {@code n} entries and cleans up the processed set (IC4 fix). */
    private void evictFirst(int n) {
        int del = Math.min(n, entries.size());
        List<WorkingMemoryEntry> toRemove = new ArrayList<>(entries.subList(0, del));
        entries.subList(0, del).clear();
        toRemove.forEach(e -> processed.remove(e.id())); // IC4: clean ghost references
    }

    /**
     * Tier eviction priority — lower number = evicted first.
     * ARCHIVED(0) < COMPRESSED(1) < BACKGROUND(2) < ACTIVE(3)
     */
    private static int tierPriority(WorkingMemoryTier tier) {
        return switch (tier) {
            case ARCHIVED    -> 0;
            case COMPRESSED  -> 1;
            case BACKGROUND,
                 SECONDARY   -> 2;   // SECONDARY is deprecated alias for BACKGROUND
            case ACTIVE      -> 3;
        };
    }

    public void compress(List<String> ids, String summary) {
        ids.forEach(id -> {
            entries.removeIf(e -> e.id().equals(id));
            processed.remove(id); // IC4: clean on compress too
        });
        add(new WorkingMemoryEntry(UUID.randomUUID().toString(), summary,
            WorkingMemoryTier.COMPRESSED, Origin.SYSTEM, 0.5, Instant.now(), TaintLabel.CLEAN));
    }

    public int estimatedTokenCount() {
        return entries.stream().mapToInt(e -> e.content().length() / 4).sum();
    }

    public int  size()  { return entries.size(); }
    public void clear() { entries.clear(); processed.clear(); }
}
