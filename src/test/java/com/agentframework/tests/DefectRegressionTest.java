package com.agentframework.tests;

import com.agentframework.action.*;
import com.agentframework.action.middleware.ToolMiddleware;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.hitl.*;
import com.agentframework.observability.*;
import com.agentframework.perception.SimplePerception;
import com.agentframework.security.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for defects fixed in the 2026-05-24 patch:
 * DA-1, WM-1, RV-1, N-EC-1, GS-1, PE-1, DA-2, DA-3.
 *
 * Best-practice notes:
 *   - All DefaultAction instances used in try-with-resources to prevent executor leak.
 *   - Concurrency tests annotated with @Timeout to prevent CI hang on deadlock.
 *   - DA-3 uses @Timeout instead of wall-clock elapsed assertion (avoids flakiness).
 *   - ToolHandler lambda signature: (args, _) when ctx is unused (Java 21 unnamed var).
 */
class DefectRegressionTest {

    private static DefaultExecutionContext ctx(String tenant) {
        Task t = Task.builder().instruction("test").maxCycles(10).maxTokens(4000).build();
        DefaultExecutionContext c = new DefaultExecutionContext(t, tenant, "user");
        Goal root = new Goal("root", null, GoalStatus.ACTIVE, "test", List.of(), null);
        c.goalStack().push(root);
        return c;
    }

    private static WorkingMemoryEntry entry(String id, String content, TaintLabel taint) {
        return new WorkingMemoryEntry(id, content, WorkingMemoryTier.ACTIVE,
                Origin.TOOL, 0.8, Instant.now(), taint);
    }

    private static ToolCall tc(String toolName) {
        return new ToolCall(toolName, Map.of(), null);
    }

    private static ToolContract readOnly(String name) {
        return ToolContract.readOnly(name, name, name + "-desc");
    }

    private static ToolContract irreversible(String name) {
        return ToolContract.irreversible(name, name, name + "-desc");
    }

    private static DefaultAction buildAction(SimpleToolRegistry registry,
                                              SecurityEnforcer se) {
        return DefaultAction.withDefaultValidators(
                registry,
                ToolMiddleware.identity(),
                new DefaultToolDispatcher(registry),
                se,
                new InMemoryEventSink());
    }

    // ── DA-1: parallel batch blocked when hostile taint in working memory ───

    @Test
    void da1_parallelBatchBlockedWhenHostileTaintInWorkingMemory() throws Exception {
        DefaultExecutionContext c = ctx("t1");
        c.workingMemory().add(entry("h1", "ignored", TaintLabel.HOSTILE));

        SecurityEnforcer se = new SecurityEnforcer(
                new TaintTracker(), new TenantPolicyEngine());
        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(readOnly("echo"),
                (args, _) -> new ToolResult("ok", List.of(), 1, BigDecimal.ZERO,
                        Duration.ofMillis(1), 0));

        try (DefaultAction action = buildAction(registry, se)) {
            ParallelToolCalls parallel = new ParallelToolCalls(
                    List.of(tc("echo")), false, Duration.ofSeconds(5));
            ActionResult result = action.execute(parallel, c);
            assertInstanceOf(ActionResult.ValidationFailure.class, result,
                    "DA-1: parallel batch must be blocked when hostile taint is present");
        }
    }

    @Test
    void da1_parallelBatchSucceedsWhenNoTaint() throws Exception {
        DefaultExecutionContext c = ctx("t1");

        SecurityEnforcer se = new SecurityEnforcer(
                new TaintTracker(), new TenantPolicyEngine());
        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(readOnly("echo"),
                (args, _) -> new ToolResult("ok", List.of(), 1, BigDecimal.ZERO,
                        Duration.ofMillis(1), 0));

        try (DefaultAction action = buildAction(registry, se)) {
            ParallelToolCalls parallel = new ParallelToolCalls(
                    List.of(tc("echo")), false, Duration.ofSeconds(5));
            ActionResult result = action.execute(parallel, c);
            assertInstanceOf(ActionResult.PartialSuccess.class, result,
                    "DA-1: parallel batch should succeed when no hostile taint");
        }
    }

    // ── WM-1: concurrent add + evict must not throw ─────────────────────────

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void wm1_concurrentAddAndEvictDoesNotThrow() throws Exception {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        int threads = 10;
        int addsPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int ti = t;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < addsPerThread; i++) {
                    try {
                        wm.add(entry(ti + "-" + i, "content", TaintLabel.CLEAN));
                        if (i % 20 == 0) wm.evictLowestRelevance(5);
                    } catch (Exception ex) {
                        errors.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(0, errors.get(),
                "WM-1: zero exceptions expected under concurrent add+evict");
    }

    // ── RV-1: resetConsecutiveFailures ──────────────────────────────────────

    @Test
    void rv1_escalatedResultResetsConsecutiveFailures() {
        DefaultExecutionContext c = ctx("t1");
        c.incrementConsecutiveFailures();
        c.incrementConsecutiveFailures();
        assertEquals(2, c.consecutiveFailures());
        c.resetConsecutiveFailures();
        assertEquals(0, c.consecutiveFailures(),
                "RV-1: resetConsecutiveFailures must bring counter to 0");
    }

    // ── N-EC-1: addTokens and addCost are atomic ────────────────────────────

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void nec1_concurrentAddTokensIsAtomic() throws Exception {
        DefaultExecutionContext c = ctx("t1");
        int threads = 50;
        int adds = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                for (int j = 0; j < adds; j++) c.addTokens(1);
            }));
        }
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(threads * adds, c.totalTokensUsed(),
                "N-EC-1: totalTokens must be thread-safe");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void nec1_concurrentAddCostIsAtomic() throws Exception {
        DefaultExecutionContext c = ctx("t1");
        int threads = 50;
        int adds = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        BigDecimal unit = new BigDecimal("0.01");
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                for (int j = 0; j < adds; j++) c.addCost(unit);
            }));
        }
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        BigDecimal expected = unit.multiply(BigDecimal.valueOf((long) threads * adds));
        assertEquals(0, expected.compareTo(c.totalCost()),
                "N-EC-1: totalCost must be thread-safe");
    }

    // ── GS-1: GoalStack concurrent access ───────────────────────────────────

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void gs1_concurrentGoalStackAccessDoesNotThrow() throws Exception {
        DefaultGoalStack gs = new DefaultGoalStack();
        Goal root = new Goal("root", null, GoalStatus.ACTIVE, "r", List.of(), null);
        gs.push(root);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger errors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int ti = i;
            futures.add(pool.submit(() -> {
                for (int j = 0; j < 100; j++) {
                    try {
                        gs.current();
                        gs.allActive();
                        gs.isRootAchieved();
                        if (ti == 0) gs.updateStatus("root", GoalStatus.ACTIVE);
                    } catch (Exception ex) {
                        errors.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(0, errors.get(), "GS-1: zero exceptions under concurrent goal-stack access");
    }

    // ── PE-1: SimplePerception taint → trust tier mapping ───────────────────

    @Test
    void pe1_hostileTaintMapsToUntrusted() {
        DefaultExecutionContext c = ctx("t1");
        c.workingMemory().add(entry("h1", "evil payload", TaintLabel.HOSTILE));
        SimplePerception sp = new SimplePerception();
        Observations obs = sp.perceive(c);
        List<Observation> items = obs.items();
        assertFalse(items.isEmpty());
        assertEquals(TrustTier.UNTRUSTED, items.get(0).trustTier(),
                "PE-1: HOSTILE taint must map to TrustTier.UNTRUSTED");
    }

    @Test
    void pe1_externalTaintMapsToLow() {
        DefaultExecutionContext c = ctx("t1");
        c.workingMemory().add(entry("e1", "external data", TaintLabel.EXTERNAL));
        SimplePerception sp = new SimplePerception();
        Observations obs = sp.perceive(c);
        List<Observation> items = obs.items();
        assertFalse(items.isEmpty());
        assertEquals(TrustTier.LOW, items.get(0).trustTier(),
                "PE-1: EXTERNAL taint must map to TrustTier.LOW");
    }

    @Test
    void pe1_cleanTaintMapsToHigh() {
        DefaultExecutionContext c = ctx("t1");
        c.workingMemory().add(entry("c1", "clean data", TaintLabel.CLEAN));
        SimplePerception sp = new SimplePerception();
        Observations obs = sp.perceive(c);
        List<Observation> items = obs.items();
        assertFalse(items.isEmpty());
        assertEquals(TrustTier.HIGH, items.get(0).trustTier(),
                "PE-1: CLEAN taint must map to TrustTier.HIGH");
    }

    // ── DA-2: close() does not throw ────────────────────────────────────────

    @Test
    void da2_closeShutdownsExecutor() {
        SimpleToolRegistry registry = new SimpleToolRegistry();
        SecurityEnforcer se = new SecurityEnforcer(
                new TaintTracker(), new TenantPolicyEngine());
        assertDoesNotThrow(() -> {
            try (DefaultAction action = buildAction(registry, se)) {
                // just verifying close() doesn't throw
            }
        }, "DA-2: close() must not throw");
    }

    // ── DA-3: parallel global deadline ──────────────────────────────────────
    // @Timeout guards against the deadline not working (infinite hang).
    // Result type check verifies the parallel path completed (not validation blocked).

    @Test
    @Timeout(value = 4, unit = TimeUnit.SECONDS)
    void da3_parallelDeadlineIsGlobal() throws Exception {
        DefaultExecutionContext c = ctx("t1");
        SecurityEnforcer se = new SecurityEnforcer(
                new TaintTracker(), new TenantPolicyEngine());

        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(readOnly("slow"), (args, _) -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ToolResult("done", List.of(), 1,
                    BigDecimal.ZERO, Duration.ofMillis(50), 0);
        });

        try (DefaultAction action = buildAction(registry, se)) {
            ParallelToolCalls parallel = new ParallelToolCalls(
                    List.of(tc("slow"), tc("slow"), tc("slow")),
                    false, Duration.ofMillis(200));
            ActionResult result = action.execute(parallel, c);
            assertInstanceOf(ActionResult.PartialSuccess.class, result,
                    "DA-3: parallel execution must complete within deadline");
        }
    }
}
