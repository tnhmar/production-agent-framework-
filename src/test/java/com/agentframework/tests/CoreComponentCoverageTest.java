package com.agentframework.tests;

import com.agentframework.core.*;
import com.agentframework.foundation.*;

import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers core components that had thin or zero explicit tests.
 *
 * Actual record signatures (from compiler errors + source inspection):
 *   ToolCall(String toolName, Map<String,Object> arguments, String reasoningTrace)
 *   AskClarification(String question)  -- 1 field
 *   Budget(int maxCycles, int maxTokens, Duration maxDuration, BigDecimal maxCost)
 *   ValidationResult.Failed(String reason, List<String> violations)
 *   ValidationResult.NeedsCorrection(String reason, Decision suggestedRevision)
 *   DefaultLivenessDetector.checkStagnation(String,String,Decision,ExecutionContext)
 *   DefaultLivenessDetector.checkStuck(String,String,Decision,ExecutionContext)
 *   DefaultBeliefState has no getBySubject() — replaced with all(minConf) filter
 */
class CoreComponentCoverageTest {

    // ── DefaultGoalStack ─────────────────────────────────────────────────────

    @Test
    void goalStack_pushCurrentDepthPop() {
        DefaultGoalStack gs = new DefaultGoalStack();
        assertEquals(0, gs.depth());
        assertTrue(gs.current().isEmpty());

        Goal g1 = goal("root", GoalStatus.ACTIVE);
        Goal g2 = goal("sub1", GoalStatus.ACTIVE);
        gs.push(g1);
        gs.push(g2);
        assertEquals(2, gs.depth());
        assertEquals("sub1", gs.current().get().id());

        gs.pop();
        assertEquals(1, gs.depth());
        assertEquals("root", gs.current().get().id());
    }

    @Test
    void goalStack_updateStatus() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(goal("root", GoalStatus.ACTIVE));
        gs.updateStatus("root", GoalStatus.COMPLETED);
        assertTrue(gs.isRootAchieved());
    }

    @Test
    void goalStack_allActiveFiltersCorrectly() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(goal("root",   GoalStatus.ACTIVE));
        gs.push(goal("sub",    GoalStatus.PENDING));
        gs.push(goal("done",   GoalStatus.COMPLETED));
        assertEquals(2, gs.allActive().size());
    }

    @Test
    void goalStack_updateStatusNonExistentIsNoOp() {
        DefaultGoalStack gs = new DefaultGoalStack();
        assertDoesNotThrow(() -> gs.updateStatus("no-such-id", GoalStatus.FAILED));
    }

    @Test
    void goalStack_isRootAchieved_falseWhenNotCompleted() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(goal("root", GoalStatus.ACTIVE));
        assertFalse(gs.isRootAchieved());
    }

    @Test
    void goalStack_allReturnsAllGoals() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(goal("g1", GoalStatus.ACTIVE));
        gs.push(goal("g2", GoalStatus.PENDING));
        assertEquals(2, gs.all().size());
    }

    // ── DefaultBeliefState ───────────────────────────────────────────────────

    @Test
    void beliefState_assertAndRetrieve() {
        DefaultBeliefState bs = new DefaultBeliefState();
        Belief b = new Belief(UUID.randomUUID().toString(), "sky", "color",
                "blue", 0.9, "observation", Instant.now(), false);
        bs.assertBelief(b);
        List<Belief> all = bs.all(0.0);
        assertEquals(1, all.size());
        assertEquals("blue", all.get(0).object());
    }

    @Test
    void beliefState_conflictResolutionHigherConfidenceWins() {
        DefaultBeliefState bs = new DefaultBeliefState();
        Belief low  = new Belief(UUID.randomUUID().toString(), "sky", "color",
                "grey", 0.4, "s", Instant.now(), false);
        Belief high = new Belief(UUID.randomUUID().toString(), "sky", "color",
                "blue", 0.9, "s", Instant.now(), false);
        bs.assertBelief(low);
        Belief winner = bs.assertBelief(high);
        assertEquals("blue", winner.object(), "Higher confidence belief must win");
    }

    @Test
    void beliefState_retract() {
        DefaultBeliefState bs = new DefaultBeliefState();
        String id = UUID.randomUUID().toString();
        bs.assertBelief(
            new Belief(id, "x", "y", "z", 0.8, "s", Instant.now(), false));
        bs.retract(id);
        assertEquals(0, bs.all(0.0).size());
    }

    @Test
    void beliefState_filterBySubjectViaAll() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(
            new Belief(UUID.randomUUID().toString(), "sky", "color", "blue", 0.8, "s", Instant.now(), false));
        bs.assertBelief(
            new Belief(UUID.randomUUID().toString(), "ground", "color", "brown", 0.8, "s", Instant.now(), false));
        long skyCount = bs.all(0.0).stream()
                .filter(b -> "sky".equals(b.subject())).count();
        assertEquals(1, skyCount, "Should have exactly one belief about sky");
    }

    @Test
    void beliefState_allWithMinConfidenceFilter() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(
            new Belief(UUID.randomUUID().toString(), "a", "p", "v", 0.3, "s", Instant.now(), false));
        bs.assertBelief(
            new Belief(UUID.randomUUID().toString(), "b", "p", "v", 0.9, "s", Instant.now(), false));
        assertEquals(1, bs.all(0.5).size());
    }

    // ── DefaultLivenessDetector ──────────────────────────────────────────────
    //
    // Real signature from compiler error:
    //   checkStagnation(String runId, String goalId, Decision decision, ExecutionContext ctx)
    //   checkStuck(String runId, String goalId, Decision decision, ExecutionContext ctx)

    @Test
    void livenessDetector_stagnationAfterThreshold() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(2, 5);
        DefaultExecutionContext c = ctx();
        FinalAnswer fa = new FinalAnswer("done");
        assertFalse(ld.checkStagnation(c.runId(), "root", fa, c));
        ld.checkStagnation(c.runId(), "root", fa, c);
        ld.checkStagnation(c.runId(), "root", fa, c);
        assertTrue(ld.checkStagnation(c.runId(), "root", fa, c),
                "Liveness: stagnation must trigger after threshold");
    }

    @Test
    void livenessDetector_stuckAfterThreshold() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(5, 2);
        DefaultExecutionContext c = ctx();
        AskClarification ask = new AskClarification("q");
        ld.checkStuck(c.runId(), "root", ask, c);
        ld.checkStuck(c.runId(), "root", ask, c);
        ld.checkStuck(c.runId(), "root", ask, c);
        assertTrue(ld.checkStuck(c.runId(), "root", ask, c),
                "Liveness: stuck must trigger after threshold");
    }

    @Test
    void livenessDetector_substantiveDecisionResetsStuck() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(5, 2);
        DefaultExecutionContext c = ctx();
        AskClarification ask = new AskClarification("q");
        ld.checkStuck(c.runId(), "root", ask, c);
        ld.checkStuck(c.runId(), "root", ask, c);
        ToolCall tc = new ToolCall("echo", Map.of(), null);
        ld.checkStuck(c.runId(), "root", tc, c);
        assertFalse(ld.checkStuck(c.runId(), "root", ask, c));
    }

    // ── ContextWindowManager ─────────────────────────────────────────────────

    @Test
    void contextWindowManager_noEvictionBelowThreshold() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        for (int i = 0; i < 10; i++) wm.add(wmEntry("e" + i, "hello world"));
        int before = wm.size();
        new ContextWindowManager().manage(wm, 100_000);
        assertEquals(before, wm.size(), "CWM: no eviction below threshold");
    }

    @Test
    void contextWindowManager_evictsWhenOver70Percent() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        for (int i = 0; i < 100; i++) wm.add(wmEntry("e" + i, "a".repeat(100)));
        int before = wm.size();
        new ContextWindowManager().manage(wm, 1000);
        assertTrue(wm.size() < before, "CWM: eviction must reduce working memory size");
    }

    @Test
    void contextWindowManager_maxTokensZeroIsNoOp() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        for (int i = 0; i < 5; i++) wm.add(wmEntry("e" + i, "hello"));
        int before = wm.size();
        new ContextWindowManager().manage(wm, 0);
        assertEquals(before, wm.size(), "CWM: maxTokens=0 must skip eviction");
    }

    // ── ValidationResult ─────────────────────────────────────────────────────
    //
    // Real signatures:
    //   ValidationResult.Failed(String reason, List<String> violations)
    //   ValidationResult.NeedsCorrection(String reason, Decision suggestedRevision)

    @Test
    void validationResult_allPermits() {
        ValidationResult passed = new ValidationResult.Passed();
        ValidationResult failed = new ValidationResult.Failed(
                "reason", List.of("v1", "v2"));
        ValidationResult needs = new ValidationResult.NeedsCorrection(
                "fix this", new FinalAnswer("try again"));
        assertInstanceOf(ValidationResult.Passed.class, passed);
        assertEquals("reason",   ((ValidationResult.Failed) failed).reason());
        assertEquals("fix this", ((ValidationResult.NeedsCorrection) needs).reason());
    }

    // ── DefaultExecutionContext snapshot round-trip ──────────────────────────

    @Test
    void executionContext_snapshotRoundTrip() {
        DefaultExecutionContext c = ctx();
        c.addTokens(42);
        c.addCost(new BigDecimal("1.23"));
        c.incrementConsecutiveFailures();
        c.incrementStagnantCycles();
        c.incrementStuckCycles();
        c.incrementRevisionCount();

        ExecutionContext.Snapshot snap = c.checkpoint();
        assertEquals(DefaultExecutionContext.SNAPSHOT_SCHEMA_VERSION, snap.schemaVersion());
        assertEquals(42, snap.totalTokens());
        assertEquals(0, new BigDecimal("1.23").compareTo(snap.totalCost()));
        assertEquals(1, snap.consecutiveFailures());
        assertEquals(1, snap.stagnantCycles());
        assertEquals(1, snap.stuckCycles());
        assertEquals(1, snap.revisionCount());

        DefaultExecutionContext c2 = ctx();
        c2.restoreFromSnapshot(snap);
        assertEquals(42, c2.totalTokensUsed());
        assertEquals(1, c2.consecutiveFailures());
    }

    @Test
    void executionContext_integrityHashIsDeterministic() {
        DefaultExecutionContext c = ctx();
        ExecutionContext.Snapshot snap = c.checkpoint();
        String h1 = snap.integrityHash();
        String h2 = DefaultExecutionContext.computeSnapshotHash(snap);
        assertEquals(h1, h2, "Integrity hash must be deterministic");
    }

    // ── Goal: withStatus and withSuccessCriteria ─────────────────────────────

    @Test
    void goal_withStatus() {
        Goal g = new Goal("g1", null, GoalStatus.PENDING, "do x", List.of(), null);
        assertEquals(GoalStatus.PENDING, g.status());
        Goal updated = g.withStatus(GoalStatus.COMPLETED);
        assertEquals(GoalStatus.COMPLETED, updated.status());
        assertEquals("g1", updated.id(), "withStatus must preserve id");
    }

    @Test
    void goal_withSuccessCriteria() {
        Goal g = new Goal("g2", null, GoalStatus.ACTIVE, "x", List.of(), null);
        Goal updated = g.withSuccessCriteria("done when Y");
        assertEquals("done when Y", updated.successCriteria());
    }

    // ── Budget ────────────────────────────────────────────────────────────────
    //
    // Real signature: Budget(int maxCycles, int maxTokens, Duration maxDuration, BigDecimal maxCost)

    @Test
    void budget_accessors() {
        Budget b = new Budget(100, 4000, Duration.ofMinutes(5), new BigDecimal("10.00"));
        assertEquals(new BigDecimal("10.00"), b.maxCost());
        assertEquals(100, b.maxCycles());
        assertEquals(4000, b.maxTokens());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Goal goal(String id, GoalStatus status) {
        return new Goal(id, null, status, id, List.of(), null);
    }

    private static WorkingMemoryEntry wmEntry(String id, String content) {
        return new WorkingMemoryEntry(id, content,
                WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5,
                Instant.now(), TaintLabel.CLEAN);
    }

    private static DefaultExecutionContext ctx() {
        Task t = Task.builder().instruction("test").maxCycles(10).maxTokens(4000).build();
        DefaultExecutionContext c = new DefaultExecutionContext(t, "tenant", "user");
        c.goalStack().push(
            new Goal("root", null, GoalStatus.ACTIVE, "root", List.of(), null));
        return c;
    }
}
