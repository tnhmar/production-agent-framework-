package com.agentframework.tests;

import com.agentframework.core.*;
import com.agentframework.foundation.*;

import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive concurrency and branch coverage for DefaultWorkingMemory.
 */
class WorkingMemoryConcurrencyTest {

    private static WorkingMemoryEntry entry(String id, WorkingMemoryTier tier,
            double relevance, Origin origin, TaintLabel taint) {
        return new WorkingMemoryEntry(id, "content-" + id, tier, origin,
                relevance, Instant.now(), taint);
    }

    // ── WM-1 stress ──────────────────────────────────────────────────────────

    @Test
    void wm1_stressAddAndEvictOldest() throws Exception {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        int threads = 12;
        int adds    = 300;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger errors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int ti = t;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < adds; i++) {
                    try {
                        wm.add(entry(ti + "-" + i, WorkingMemoryTier.ACTIVE,
                                0.5, Origin.TOOL, TaintLabel.CLEAN));
                        if (i % 15 == 0) wm.evictOldest(3);
                        if (i % 25 == 0) wm.evictLowestRelevance(3);
                    } catch (Exception ex) {
                        errors.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(0, errors.get(),
                "WM-1 stress: zero exceptions under concurrent add+evict");
    }

    // ── evictOldest ──────────────────────────────────────────────────────────

    @Test
    void evictOldest_removesNOldest() throws Exception {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        for (int i = 0; i < 5; i++) {
            Thread.sleep(1);
            wm.add(entry("e" + i, WorkingMemoryTier.ACTIVE, 0.5, Origin.TOOL, TaintLabel.CLEAN));
        }
        wm.evictOldest(2);
        assertEquals(3, wm.size());
    }

    // ── evictLowestRelevance: tier-aware ─────────────────────────────────────

    @Test
    void evictLowestRelevance_archiveEvictedFirst() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(entry("active",   WorkingMemoryTier.ACTIVE,   0.9, Origin.TOOL, TaintLabel.CLEAN));
        wm.add(entry("archived", WorkingMemoryTier.ARCHIVED,  0.9, Origin.TOOL, TaintLabel.CLEAN));
        wm.add(entry("bg",       WorkingMemoryTier.BACKGROUND,0.9, Origin.TOOL, TaintLabel.CLEAN));
        wm.evictLowestRelevance(1);
        assertTrue(wm.getAll().stream().noneMatch(e -> e.id().equals("archived")),
                "ARCHIVED entry must be evicted first");
    }

    @Test
    void evictLowestRelevance_withinSameTierLowestFirst() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(entry("low",  WorkingMemoryTier.ACTIVE, 0.1, Origin.TOOL, TaintLabel.CLEAN));
        wm.add(entry("high", WorkingMemoryTier.ACTIVE, 0.9, Origin.TOOL, TaintLabel.CLEAN));
        wm.evictLowestRelevance(1);
        assertTrue(wm.getAll().stream().noneMatch(e -> e.id().equals("low")),
                "Lowest relevance entry must be evicted first within same tier");
    }

    // ── compress ─────────────────────────────────────────────────────────────

    @Test
    void compress_replacesEntriesWithSummary() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(entry("a", WorkingMemoryTier.ACTIVE, 0.5, Origin.TOOL, TaintLabel.CLEAN));
        wm.add(entry("b", WorkingMemoryTier.ACTIVE, 0.5, Origin.TOOL, TaintLabel.CLEAN));
        wm.markProcessed("a");
        wm.compress(List.of("a", "b"), "summary of a and b");
        assertEquals(1, wm.size(),
                "compress: must replace 2 entries with 1 compressed entry");
        assertEquals(WorkingMemoryTier.COMPRESSED, wm.getAll().get(0).tier());
        assertFalse(wm.isProcessed("a"),
                "IC4: evicted entry must be removed from processed set");
    }

    // ── clear ────────────────────────────────────────────────────────────────

    @Test
    void clear_emptiesEverything() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(entry("x", WorkingMemoryTier.ACTIVE, 0.5, Origin.USER, TaintLabel.CLEAN));
        wm.markProcessed("x");
        wm.clear();
        assertEquals(0, wm.size());
        assertFalse(wm.isProcessed("x"));
    }

    // ── getByOrigin ──────────────────────────────────────────────────────────

    @Test
    void getByOrigin_filtersCorrectly() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(entry("u1", WorkingMemoryTier.ACTIVE, 0.5, Origin.USER,   TaintLabel.CLEAN));
        wm.add(entry("t1", WorkingMemoryTier.ACTIVE, 0.5, Origin.TOOL,   TaintLabel.CLEAN));
        wm.add(entry("s1", WorkingMemoryTier.ACTIVE, 0.5, Origin.SYSTEM, TaintLabel.CLEAN));
        assertEquals(1, wm.getByOrigin(Origin.USER).size());
        assertEquals(1, wm.getByOrigin(Origin.TOOL).size());
        assertEquals(0, wm.getByOrigin(Origin.AGENT).size());
    }

    // ── markProcessed / isProcessed / getUnprocessed ─────────────────────────

    @Test
    void processedTracking() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(entry("p1", WorkingMemoryTier.ACTIVE, 0.5, Origin.USER, TaintLabel.CLEAN));
        wm.add(entry("p2", WorkingMemoryTier.ACTIVE, 0.5, Origin.USER, TaintLabel.CLEAN));
        assertEquals(2, wm.getUnprocessed().size());
        assertFalse(wm.isProcessed("p1"));
        wm.markProcessed("p1");
        assertTrue(wm.isProcessed("p1"));
        assertEquals(1, wm.getUnprocessed().size());
    }

    // ── estimatedTokenCount ──────────────────────────────────────────────────

    @Test
    void estimatedTokenCount_approximation() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        for (int i = 0; i < 10; i++) {
            wm.add(new WorkingMemoryEntry("e" + i, "a".repeat(40),
                    WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5,
                    Instant.now(), TaintLabel.CLEAN));
        }
        assertEquals(100, wm.estimatedTokenCount());
    }

    // ── IC4: evictOldest cleans processed set ─────────────────────────────────

    @Test
    void ic4_evictOldestCleansProcessedSet() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(entry("old", WorkingMemoryTier.ACTIVE, 0.5, Origin.TOOL, TaintLabel.CLEAN));
        wm.markProcessed("old");
        wm.add(entry("new", WorkingMemoryTier.ACTIVE, 0.5, Origin.TOOL, TaintLabel.CLEAN));
        wm.evictOldest(1);
        assertFalse(wm.isProcessed("old"),
                "IC4: evicted entry must be cleaned from processed set");
    }

    // ── SECONDARY tier alias ─────────────────────────────────────────────────

    @Test
    void secondaryTierAliasIsBackground() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(entry("s", WorkingMemoryTier.SECONDARY, 0.5, Origin.TOOL, TaintLabel.CLEAN));
        wm.add(entry("a", WorkingMemoryTier.ARCHIVED,  0.5, Origin.TOOL, TaintLabel.CLEAN));
        wm.add(entry("x", WorkingMemoryTier.ACTIVE,    0.9, Origin.TOOL, TaintLabel.CLEAN));
        wm.evictLowestRelevance(1);
        assertTrue(wm.getAll().stream().noneMatch(e -> e.id().equals("a")),
                "ARCHIVED must be evicted before SECONDARY");
    }
}
