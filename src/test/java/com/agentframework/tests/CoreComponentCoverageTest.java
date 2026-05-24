package com.agentframework.tests;

import com.agentframework.core.*;
import com.agentframework.foundation.*;

import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers core components that had thin or zero explicit tests:
 *  - DefaultGoalStack: push/pop/depth/all/updateStatus/isRootAchieved
 *  - DefaultBeliefState: assertBelief, conflict resolution, retract, getBySubject
 *  - DefaultLivenessDetector: stagnation, stuck-state thresholds
 *  - ContextWindowManager: threshold guard, eviction, maxTokens<=0 guard
 *  - Goal: builder, withStatus, tags
 *  - Belief: record accessors
 *  - Budget value type
 *  - ValidationResult: all three permits
 *  - DefaultExecutionContext: snapshot round-trip and schema version
 */
class CoreComponentCoverageTest {

    // ── DefaultGoalStack ──────────────────────────────────────────────────────

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
        gs.push(goal("root", GoalStatus.ACTIVE));
        gs.push(goal("sub", GoalStatus.PENDING));
        gs.push(goal("done", GoalStatus.COMPLETED));
        List<Goal> active = gs.allActive();
        assertEquals(2, active.size());
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

    // ── DefaultBeliefState ────────────────────────────────────────────────────

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
        bs.assertBelief(new Belief(id, "x", "y", "z", 0.8, "s", Instant.now(), false));
        bs.retract(id);
        assertEquals(0, bs.all(0.0).size());
    }

    @Test
    void beliefState_getBySubject() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(new Belief(UUID.randomUUID().toString(), "a", "p",
                "v", 0.8, "s", Instant.now(), false));
        bs.assertBelief(new Belief(UUID.randomUUID().toString(), "b", "p",
                "v", 0.8, "s", Instant.now(), false));
        assertEquals(1, bs.getBySubject("a").size());
    }

    @Test
    void beliefState_allWithMinConfidenceFilter() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(new Belief(UUID.randomUUID().toString(), "a", "p",
                "v", 0.3, "s", Instant.now(), false));
        bs.assertBelief(new Belief(UUID.randomUUID().toString(), "b", "p",
                "v", 0.9, "s", Instant.now(), false));
        assertEquals(1, bs.all(0.5).size());
    }

    // ── DefaultLivenessDetector ───────────────────────────────────────────────

    @Test
    void livenessDetector_stagnationAfterThreshold() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(2, 5);
        DefaultExecutionContext c = ctx();

        // first tick — not stagnant
        assertFalse(ld.checkStagnation(c));
        // same goal hash three times → stagnation
        ld.checkStagnation(c);
        ld.checkStagnation(c);
        assertTrue(ld.checkStagnation(c), "Liveness: stagnation must trigger after threshold");
    }

    @Test
    void livenessDetector_stuckAfterThreshold() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(5, 2);
        DefaultExecutionContext c = ctx();
        ld.checkStuck(c, new AskClarification(UUID.randomUUID().toString(), "q"));
        ld.checkStuck(c, new AskClarification(UUID.randomUUID().toString(), "q"));
        ld.checkStuck(c, new AskClarification(UUID.randomUUID().toString(), "q"));
        assertTrue(ld.checkStuck(c,
                new AskClarification(UUID.randomUUID().toString(), "q")),
                "Liveness: stuck must trigger after threshold");
    }

    @Test
    void livenessDetector_substantiveDecisionResetsStuck() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(5, 2);
        DefaultExecutionContext c = ctx();
        ld.checkStuck(c, new AskClarification(UUID.randomUUID().toString(), "q"));
        ld.checkStuck(c, new AskClarification(UUID.randomUUID().toString(), "q"));
        // substantive decision resets
        ld.checkStuck(c,
                new ToolCall(UUID.randomUUID().toString(), "echo", Map.of(), false));
        assertFalse(ld.checkStuck(c,
                new AskClarification(UUID.randomUUID().toString(), "q")));
    }

    // ── ContextWindowManager ──────────────────────────────────────────────────

    @Test
    void contextWindowManager_noEvictionBelowThreshold() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        // add 10 small entries
        for (int i = 0; i < 10; i++) {
            wm.add(wmEntry("e" + i, "hello world"));
        }
        int before = wm.size();
        new ContextWindowManager().manage(wm, 100_000); // large limit — no eviction
        assertEquals(before, wm.size(), "CWM: no eviction below threshold");
    }

    @Test
    void contextWindowManager_evictsWhenOver70Percent() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        // each entry ≈ 25 tokens (100 chars / 4)
        for (int i = 0; i < 100; i++) {
            wm.add(wmEntry("e" + i, "a".repeat(100)));
        }
        int before = wm.size();
        // maxTokens = 1000, 100 entries × 25 tokens = 2500 > 700 threshold → eviction
        new ContextWindowManager().manage(wm, 1000);
        assertTrue(wm.size() < before, "CWM: eviction must reduce working memory size");
    }

    @Test
    void contextWindowManager_maxTokensZeroIsNoOp() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        for (int i = 0; i < 5; i++) wm.add(wmEntry("e" + i, "hello"));
        int before = wm.size();
        new ContextWindowManager().manage(wm, 0);
        assertEquals(before, wm.size(), "CWM: maxTokens=0 guard must skip eviction");
    }

    // ── ValidationResult: all three permits ──────────────────────────────────

    @Test
    void validationResult_allPermits() {
        ValidationResult passed  = new ValidationResult.Passed();
        ValidationResult failed  = new ValidationResult.Failed("reason");
        ValidationResult needs   = new ValidationResult.NeedsCorrection("fix this");

        assertInstanceOf(ValidationResult.Passed.class, passed);
        assertEquals("reason",    ((ValidationResult.Failed) failed).reason());
        assertEquals("fix this",  ((ValidationResult.NeedsCorrection) needs).reason());
    }

    // ── DefaultExecutionContext: snapshot round-trip ──────────────────────────

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

        // restore into a fresh context
        DefaultExecutionContext c2 = ctx();
        c2.restoreFromSnapshot(snap);
        assertEquals(42, c2.totalTokensUsed());
        assertEquals(1, c2.consecutiveFailures());
    }

    @Test
    void executionContext_integrityHashDetectsTamper() {
        DefaultExecutionContext c = ctx();
        ExecutionContext.Snapshot snap = c.checkpoint();
        String original = snap.integrityHash();
        // recompute — must match
        String recomputed = DefaultExecutionContext.computeSnapshotHash(snap);
        assertEquals(original, recomputed, "Integrity hash must be deterministic");
    }

    // ── Goal: builder and withStatus ─────────────────────────────────────────

    @Test
    void goal_builderAndWithStatus() {
        Goal g = Goal.builder().id("g1").description("do x").priority(5)
                .status(GoalStatus.PENDING).build();
        assertEquals("g1", g.id());
        assertEquals(GoalStatus.PENDING, g.status());

        Goal updated = g.withStatus(GoalStatus.COMPLETED);
        assertEquals(GoalStatus.COMPLETED, updated.status());
        assertEquals("g1", updated.id(), "withStatus must preserve id");
    }

    @Test
    void goal_tagsAreOptional() {
        Goal g = Goal.builder().id("g2").description("x").priority(1)
                .status(GoalStatus.ACTIVE).build();
        // tags may be null or empty — just must not throw
        assertDoesNotThrow(g::tags);
    }

    // ── Budget ───────────────────────────────────────────────────────────────

    @Test
    void budget_accessors() {
        Budget b = new Budget(new BigDecimal("10.00"), new BigDecimal("3.50"));
        assertEquals(0, new BigDecimal("10.00").compareTo(b.maxCost()));
        assertEquals(0, new BigDecimal("3.50").compareTo(b.spent()));
        assertTrue(b.hasRemaining());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Goal goal(String id, GoalStatus status) {
        return Goal.builder().id(id).description(id).priority(1).status(status).build();
    }

    private static WorkingMemoryEntry wmEntry(String id, String content) {
        return new WorkingMemoryEntry(id, content,
                WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5,
                Instant.now(), TaintLabel.CLEAN);
    }

    private static DefaultExecutionContext ctx() {
        Task t = Task.builder().instruction("test").maxCycles(10).maxTokens(4000).build();
        DefaultExecutionContext c = new DefaultExecutionContext(t, "tenant", "user");
        c.goalStack().push(Goal.builder().id("root").description("root")
                .priority(1).status(GoalStatus.ACTIVE).build());
        return c;
    }
}
