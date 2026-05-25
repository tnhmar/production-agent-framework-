package com.agentframework.core;

import com.agentframework.foundation.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Default in-memory working memory implementation.
 *
 * <p><b>M2 fix:</b> {@link #evictLowestRelevance(int)} is tier-aware.
 * Eviction priority: ARCHIVED → COMPRESSED → BACKGROUND → ACTIVE.
 * Within the same tier, entries are sorted by ascending relevance score
 * (lowest evicted first).
 *
 * <p><b>IC4 fix:</b> evicted entries are removed from the processed set
 * to prevent ghost references in {@link #getUnprocessed()}.
 *
 * <p><b>WM-1 fix:</b> all mutation paths ({@code add}, {@code evictOldest},
 * {@code evictLowestRelevance}, {@code compress}, {@code clear}) now
 * synchronise on the {@code entries} list — the same monitor — eliminating
 * the TOCTOU race that existed when {@code add()} was unsynchronised while
 * eviction methods held the lock.
 *
 * <p><b>C-4 fix:</b> {@link #compress(List, String)} now stores the caller-supplied
 * {@code summary} as the compressed entry's content, giving semantic compression
 * rather than a silent tier change.  The original entries are removed and replaced
 * with a single COMPRESSED entry whose content is the summarised text.
 */
public class DefaultWorkingMemory implements WorkingMemory {

    private final List<WorkingMemoryEntry> entries   = new ArrayList<>();
    private final Set<String>              processed = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** WM-1 fix: synchronise add() on the same monitor as eviction methods. */
    public void add(WorkingMemoryEntry e) {
        synchronized (entries) {
            entries.add(e);
        }
    }

    public List<WorkingMemoryEntry> getAll() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    public List<WorkingMemoryEntry> getByOrigin(Origin o) {
        synchronized (entries) {
            return entries.stream().filter(e -> e.origin() == o).collect(Collectors.toList());
        }
    }

    public List<WorkingMemoryEntry> getUnprocessed() {
        synchronized (entries) {
            return entries.stream()
                .filter(e -> !processed.contains(e.id()))
                .collect(Collectors.toList());
        }
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
     * Sort order: tier priority ascending (ARCHIVED=0 ... ACTIVE=3),
     * then relevance ascending. Lowest-priority, lowest-relevance entries
     * are removed first.
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
        toRemove.forEach(e -> processed.remove(e.id()));
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
                 SECONDARY   -> 2;
            case ACTIVE      -> 3;
        };
    }

    /**
     * C-4 fix: semantic compression.
     *
     * <p>Removes all entries whose ids are in {@code ids} and inserts a single
     * new COMPRESSED entry whose {@code content} is the caller-supplied
     * {@code summary} string.  This implements the Volume 1 requirement:
     * <em>"compress rather than discard when content may still matter"</em> —
     * the summarised text is preserved in the compressed entry so the agent
     * can still reference it; the original full-text entries are released.
     *
     * @param ids     the ids of the entries to compress
     * @param summary the semantic summary produced by the caller (e.g. an LLM
     *                summarisation step) that replaces the original content
     */
    public void compress(List<String> ids, String summary) {
        Objects.requireNonNull(summary, "summary must not be null");
        synchronized (entries) {
            ids.forEach(id -> {
                entries.removeIf(e -> e.id().equals(id));
                processed.remove(id);
            });
            entries.add(new WorkingMemoryEntry(
                UUID.randomUUID().toString(),
                summary,                          // C-4: store actual summary content
                WorkingMemoryTier.COMPRESSED,
                Origin.SYSTEM,
                0.5,
                Instant.now(),
                TaintLabel.CLEAN));
        }
    }

    public int estimatedTokenCount() {
        synchronized (entries) {
            return entries.stream().mapToInt(e -> e.content().length() / 4).sum();
        }
    }

    public int size() {
        synchronized (entries) { return entries.size(); }
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
            processed.clear();
        }
    }
}
