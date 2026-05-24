package com.agentframework.tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.agentframework.core.*;
import com.agentframework.foundation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Covers untested branches in:
 *   DefaultLivenessDetector, DefaultBeliefState, DefaultGoalStack,
 *   DefaultWorkingMemory, DefaultExecutionContext, Goal.
 */
public class CoreCoverageTest {

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private static DefaultExecutionContext ctx() {
        Task t = Task.builder().instruction("test").maxCycles(50).build();
        return new DefaultExecutionContext(t, "tenant1", "user1");
    }

    private static WorkingMemoryEntry wme(String id, WorkingMemoryTier tier, Origin origin, double score) {
        return new WorkingMemoryEntry(id, "content-" + id, tier, origin, score,
                Instant.now(), TaintLabel.CLEAN);
    }

    private static Belief belief(String id, String subj, String pred, String obj, double conf) {
        return new Belief(id, subj, pred, obj, conf, "src", Instant.now(), false);
    }

    private static Goal pendingGoal(String id) {
        return new Goal(id, null, GoalStatus.PENDING, "desc-" + id, List.of(), Budget.unlimited());
    }

    // ─────────────────────────────────────────────────────────────────
    // DefaultLivenessDetector
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void livenessDetector_constructorRejectsZeroStagnant() {
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultLivenessDetector(0, 1));
    }

    @Test
    public void livenessDetector_constructorRejectsZeroStuck() {
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultLivenessDetector(1, 0));
    }

    @Test
    public void livenessDetector_hashGoalState_emptyList() {
        // Must not throw and must return a 64-char hex string
        String h = DefaultLivenessDetector.hashGoalState(List.of());
        assertNotNull(h);
        assertEquals(64, h.length());
    }

    @Test
    public void livenessDetector_hashGoalState_deterministicAndSensitive() {
        Goal g1 = pendingGoal("g1");
        Goal g2 = pendingGoal("g2");
        String h1 = DefaultLivenessDetector.hashGoalState(List.of(g1));
        String h2 = DefaultLivenessDetector.hashGoalState(List.of(g1));
        String h3 = DefaultLivenessDetector.hashGoalState(List.of(g2));
        assertEquals(h1, h2, "same input → same hash");
        assertNotEquals(h1, h3, "different goals → different hash");
    }

    @Test
    public void livenessDetector_checkStagnation_progressResetsCounter() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(3, 2);
        DefaultExecutionContext c  = ctx();
        c.incrementStagnantCycles(); // simulate prior stagnation
        // Different hashes → progress → counter reset, no termination
        Optional<TerminationReason> r = ld.checkStagnation("aaa", "bbb",
                new FinalAnswer("ok", List.of()), c);
        assertTrue(r.isEmpty(), "progress → no termination");
        assertEquals(0, c.stagnantCycles(), "counter reset on progress");
    }

    @Test
    public void livenessDetector_checkStagnation_sameHashAccumulates() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(3, 2);
        DefaultExecutionContext c  = ctx();
        FinalAnswer fa = new FinalAnswer("ok", List.of());
        // Two identical hashes → stagnantCycles reaches 2, below threshold 3
        ld.checkStagnation("X", "X", fa, c);
        ld.checkStagnation("X", "X", fa, c);
        assertEquals(2, c.stagnantCycles());
        assertTrue(ld.checkStagnation("X", "X", fa, c).isPresent(),
                "third identical hash → StagnationLimit");
    }

    @Test
    public void livenessDetector_checkStagnation_nonSubstantiveDecisionSkipped() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(1, 1);
        DefaultExecutionContext c  = ctx();
        // AskClarification is not substantive → no accumulation even with same hash
        Optional<TerminationReason> r = ld.checkStagnation("X", "X",
                new AskClarification("clarify?"), c);
        assertTrue(r.isEmpty(), "non-substantive decision → skipped");
        assertEquals(0, c.stagnantCycles(), "counter not incremented");
    }

    @Test
    public void livenessDetector_checkStuck_substantiveDecisionResetsCounter() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(3, 2);
        DefaultExecutionContext c  = ctx();
        c.incrementStuckCycles();
        Optional<TerminationReason> r = ld.checkStuck(new ToolCall("t", Map.of(), ""), c);
        assertTrue(r.isEmpty(), "substantive → no termination");
        assertEquals(0, c.stuckCycles(), "counter reset");
    }

    @Test
    public void livenessDetector_checkStuck_nonSubstantiveAccumulatesAndTerminates() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(3, 2);
        DefaultExecutionContext c  = ctx();
        AskClarification ask = new AskClarification("?");
        Optional<TerminationReason> r1 = ld.checkStuck(ask, c);
        assertTrue(r1.isEmpty(), "1 stuck cycle → not yet terminated");
        Optional<TerminationReason> r2 = ld.checkStuck(ask, c);
        assertTrue(r2.isPresent(), "2nd non-substantive cycle → Escalated");
        assertInstanceOf(TerminationReason.Escalated.class, r2.get());
    }

    @Test
    public void livenessDetector_checkStuck_parallelToolCallsIsSubstantive() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(3, 2);
        DefaultExecutionContext c  = ctx();
        c.incrementStuckCycles();
        ld.checkStuck(new ParallelToolCalls(List.of(new ToolCall("t", Map.of(), ""))), c);
        assertEquals(0, c.stuckCycles(), "ParallelToolCalls is substantive");
    }

    @Test
    public void livenessDetector_checkStuck_escalateIsSubstantive() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(3, 2);
        DefaultExecutionContext c  = ctx();
        c.incrementStuckCycles();
        ld.checkStuck(new Escalate("reason", "HIGH"), c);
        assertEquals(0, c.stuckCycles(), "Escalate is substantive");
    }

    // ─────────────────────────────────────────────────────────────────
    // DefaultBeliefState – uncovered branches
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void beliefState_getBySPO_absent() {
        DefaultBeliefState bs = new DefaultBeliefState();
        assertTrue(bs.getBySPO("sky", "color").isEmpty(), "absent → empty");
    }

    @Test
    public void beliefState_allWithMinConfFilter() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "sky",   "color",  "blue",  0.3));
        bs.assertBelief(belief("b2", "grass",  "color",  "green", 0.9));
        List<Belief> high = bs.all(0.5);
        assertEquals(1, high.size(), "only 1 above 0.5");
        assertEquals("green", high.get(0).object());
    }

    @Test
    public void beliefState_conflictLowerConfExistingWins() {
        // Existing has higher confidence → existing wins
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "sky", "color", "blue",  0.9));
        bs.assertBelief(belief("b2", "sky", "color", "green", 0.3)); // lower conf → existing stays
        assertEquals("blue", bs.getBySPO("sky", "color").get().object(),
                "lower-conf incoming → existing object retained");
        assertEquals(1, bs.conflicts().size(), "conflict still logged");
    }

    @Test
    public void beliefState_sameKeyHigherConfNoConflict_updatesEntry() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "sky", "color", "blue", 0.5));
        bs.assertBelief(belief("b2", "sky", "color", "blue", 0.8)); // same object, higher conf
        assertEquals(0, bs.conflicts().size(), "same object → no conflict");
        assertEquals(0.8, bs.getBySPO("sky", "color").get().confidence(), 1e-9,
                "higher confidence updated");
    }

    @Test
    public void beliefState_resolveConflict_clearsConflictedFlag() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "sky", "color", "blue",  0.5));
        bs.assertBelief(belief("b2", "sky", "color", "green", 0.9)); // conflict; b2 wins
        Belief winner = bs.getBySPO("sky", "color").get();
        assertTrue(winner.conflicted(), "winner is marked conflicted");
        bs.resolveConflict("sky", "color", winner.beliefId());
        assertFalse(bs.getBySPO("sky", "color").get().conflicted(),
                "conflict cleared after resolution");
    }

    @Test
    public void beliefState_resolveConflict_wrongIdIsNoop() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "sky", "color", "blue", 0.9));
        // Resolving with a non-matching id must not throw and must be a no-op
        assertDoesNotThrow(() -> bs.resolveConflict("sky", "color", "wrong-id"));
    }

    @Test
    public void beliefState_resolveConflict_absentKeyIsNoop() {
        DefaultBeliefState bs = new DefaultBeliefState();
        assertDoesNotThrow(() -> bs.resolveConflict("ghost", "predicate", "any"));
    }

    // ─────────────────────────────────────────────────────────────────
    // DefaultGoalStack – uncovered branches
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void goalStack_allActive_filtersCompleted() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(pendingGoal("g1"));
        gs.push(pendingGoal("g2"));
        gs.updateStatus("g1", GoalStatus.COMPLETED);
        List<Goal> active = gs.allActive();
        assertEquals(1, active.size(), "only 1 active/pending remains");
        assertEquals("g2", active.get(0).id());
    }

    @Test
    public void goalStack_updateStatus_nonTopGoal() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(pendingGoal("g1")); // bottom
        gs.push(pendingGoal("g2")); // top
        // Update g1 which is not the top-of-stack
        gs.updateStatus("g1", GoalStatus.FAILED);
        assertEquals(GoalStatus.FAILED,
                gs.all().stream().filter(g -> g.id().equals("g1")).findFirst().get().status(),
                "non-top goal status updated in allGoals map");
        // Top of stack (g2) should be unchanged
        assertEquals(GoalStatus.PENDING, gs.current().get().status());
    }

    @Test
    public void goalStack_updateStatus_unknownIdIsNoop() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(pendingGoal("g1"));
        assertDoesNotThrow(() -> gs.updateStatus("ghost", GoalStatus.COMPLETED));
        assertEquals(GoalStatus.PENDING, gs.current().get().status(), "g1 unaffected");
    }

    @Test
    public void goalStack_isRootAchieved_falseWhenAbsent() {
        DefaultGoalStack gs = new DefaultGoalStack();
        assertFalse(gs.isRootAchieved(), "no root → false");
    }

    @Test
    public void goalStack_isRootAchieved_falseWhenPending() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(pendingGoal("root")); // PENDING, not COMPLETED
        assertFalse(gs.isRootAchieved());
    }

    @Test
    public void goalStack_popEmpty_noThrow() {
        DefaultGoalStack gs = new DefaultGoalStack();
        assertDoesNotThrow(gs::pop, "pop on empty stack must not throw");
    }

    @Test
    public void goalStack_all_returnsSnapshot() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(pendingGoal("a"));
        gs.push(pendingGoal("b"));
        List<Goal> all = gs.all();
        assertEquals(2, all.size());
        // Mutating the returned list must not affect the stack
        all.clear();
        assertEquals(2, gs.all().size(), "snapshot is independent");
    }

    // ─────────────────────────────────────────────────────────────────
    // DefaultWorkingMemory – uncovered branches
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void workingMemory_getByOrigin() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(wme("t1", WorkingMemoryTier.ACTIVE,     Origin.TOOL,   0.9));
        wm.add(wme("u1", WorkingMemoryTier.ACTIVE,     Origin.USER,   0.8));
        wm.add(wme("t2", WorkingMemoryTier.BACKGROUND, Origin.TOOL,   0.5));
        List<WorkingMemoryEntry> toolEntries = wm.getByOrigin(Origin.TOOL);
        assertEquals(2, toolEntries.size(), "2 TOOL-origin entries");
        assertTrue(wm.getByOrigin(Origin.SYSTEM).isEmpty(), "no SYSTEM entries");
    }

    @Test
    public void workingMemory_evictLowestRelevance_tierAwareOrdering() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        // ARCHIVED should be evicted before ACTIVE regardless of score
        wm.add(wme("arch",   WorkingMemoryTier.ARCHIVED,    Origin.SYSTEM, 1.0)); // priority 0
        wm.add(wme("active", WorkingMemoryTier.ACTIVE,       Origin.TOOL,   0.1)); // priority 3
        wm.evictLowestRelevance(1);
        assertEquals(1, wm.size(), "1 entry remains");
        assertEquals("active", wm.getAll().get(0).id(),
                "ARCHIVED evicted first despite higher score");
    }

    @Test
    public void workingMemory_evictLowestRelevance_withinSameTierByScore() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(wme("hi",  WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.9));
        wm.add(wme("lo",  WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.1));
        wm.add(wme("mid", WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5));
        wm.evictLowestRelevance(1);
        assertEquals(2, wm.size());
        // The lowest-relevance entry (lo=0.1) must have been evicted
        assertTrue(wm.getAll().stream().noneMatch(e -> e.id().equals("lo")),
                "lowest-score entry evicted");
    }

    @Test
    public void workingMemory_evictMore_clampsToSize() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(wme("e1", WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5));
        wm.add(wme("e2", WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5));
        assertDoesNotThrow(() -> wm.evictOldest(100), "evicting more than size is safe");
        assertEquals(0, wm.size(), "all entries evicted");
    }

    @Test
    public void workingMemory_evictCleansProcessedSet() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(wme("e1", WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5));
        wm.markProcessed("e1");
        assertTrue(wm.isProcessed("e1"));
        wm.evictOldest(1);
        assertFalse(wm.isProcessed("e1"),
                "IC4: eviction removes ghost reference from processed set");
    }

    @Test
    public void workingMemory_estimatedTokenCount() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        // content "1234" → 4 chars / 4 = 1 token; two entries → 2 tokens
        wm.add(new WorkingMemoryEntry("a", "1234", WorkingMemoryTier.ACTIVE,
                Origin.TOOL, 0.5, Instant.now(), TaintLabel.CLEAN));
        wm.add(new WorkingMemoryEntry("b", "ABCD", WorkingMemoryTier.ACTIVE,
                Origin.TOOL, 0.5, Instant.now(), TaintLabel.CLEAN));
        assertEquals(2, wm.estimatedTokenCount());
    }

    @Test
    public void workingMemory_clear_resetsEverything() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(wme("e1", WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5));
        wm.markProcessed("e1");
        wm.clear();
        assertEquals(0, wm.size(), "entries cleared");
        assertFalse(wm.isProcessed("e1"), "processed set cleared");
    }

    @Test
    public void workingMemory_compressCleansGhostRefs() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(wme("e1", WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5));
        wm.markProcessed("e1");
        wm.compress(List.of("e1"), "summary");
        assertFalse(wm.isProcessed("e1"),
                "IC4: compress removes ghost reference from processed set");
    }

    // ─────────────────────────────────────────────────────────────────
    // DefaultExecutionContext – uncovered branches
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void executionContext_terminationReason_initiallyEmpty() {
        DefaultExecutionContext c = ctx();
        assertTrue(c.terminationReason().isEmpty());
    }

    @Test
    public void executionContext_terminationReason_setAndRetrieve() {
        DefaultExecutionContext c = ctx();
        TerminationReason r = new TerminationReason.GoalCompleted();
        c.setTerminationReason(r);
        assertTrue(c.terminationReason().isPresent());
        assertSame(r, c.terminationReason().get());
    }

    @Test
    public void executionContext_activeJobsMap() {
        DefaultExecutionContext c = ctx();
        assertTrue(c.activeJobs().isEmpty(), "initially empty");
        JobToken tok = new JobToken("job-1");
        c.activeJobs().put("job-1", tok);
        assertEquals(1, c.activeJobs().size());
        assertSame(tok, c.activeJobs().get("job-1"));
    }

    @Test
    public void executionContext_recordCycleAndTrace() {
        DefaultExecutionContext c = ctx();
        assertEquals(0, c.trace().size(), "initially empty trace");
        CycleRecord rec = CycleRecord.of(0,
                new Observations(List.of()),
                new FinalAnswer("ok", List.of()),
                new ActionResult.Escalated("none"),
                "ok");
        c.recordCycle(rec);
        assertEquals(1, c.trace().size());
        assertSame(rec, c.trace().get(0));
    }

    @Test
    public void executionContext_restoreFromSnapshot_roundTrip() {
        DefaultExecutionContext original = ctx();
        original.transitionTo(RunState.PLANNING);
        original.incrementCycle();
        original.incrementCycle();
        original.addTokens(500);
        original.addCost(BigDecimal.valueOf(2));
        original.incrementConsecutiveFailures();
        original.incrementStagnantCycles();
        original.incrementStuckCycles();
        original.incrementRevisionCount();
        original.goalStack().push(pendingGoal("root"));
        original.workingMemory().add(wme("w1", WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.7));
        original.beliefState().assertBelief(belief("bx", "a", "b", "c", 0.8));

        ExecutionContext.Snapshot snap = original.checkpoint();

        // Restore into a fresh context
        DefaultExecutionContext restored =
                new DefaultExecutionContext(Task.builder().instruction("r").build(), "t", "u");
        restored.restoreFromSnapshot(snap);

        assertEquals(snap.cycle(),                restored.cycleCount());
        assertEquals(snap.state(),                restored.currentState());
        assertEquals(snap.totalTokens(),          restored.totalTokensUsed());
        assertEquals(snap.totalCost(),            restored.totalCost());
        assertEquals(snap.consecutiveFailures(),  restored.consecutiveFailures());
        assertEquals(snap.stagnantCycles(),       restored.stagnantCycles());
        assertEquals(snap.stuckCycles(),          restored.stuckCycles());
        assertEquals(snap.revisionCount(),        restored.revisionCount());
        assertFalse(restored.goalStack().all().isEmpty(), "goals restored");
        assertFalse(restored.workingMemory().getAll().isEmpty(), "wm restored");
        assertFalse(restored.beliefState().all(0.0).isEmpty(), "beliefs restored");
    }

    @Test
    public void executionContext_computeSnapshotHash_populatedState() {
        DefaultExecutionContext c = ctx();
        c.transitionTo(RunState.PLANNING);
        c.incrementCycle();
        c.goalStack().push(pendingGoal("root"));
        c.addTokens(100);
        c.addCost(BigDecimal.ONE);
        c.incrementConsecutiveFailures();
        ExecutionContext.Snapshot snap = c.checkpoint();
        // Hash must be deterministic and match static helper
        assertEquals(snap.integrityHash(),
                DefaultExecutionContext.computeSnapshotHash(snap),
                "static helper must produce same hash as instance method");
    }

    // ─────────────────────────────────────────────────────────────────
    // Goal – uncovered branches
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void goal_nullIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new Goal(null, null, GoalStatus.PENDING, "d", List.of(), null));
    }

    @Test
    public void goal_blankIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new Goal("  ", null, GoalStatus.PENDING, "d", List.of(), null));
    }

    @Test
    public void goal_nullCollectionsNormalisedToEmpty() {
        Goal g = new Goal("g1", null, GoalStatus.PENDING, "d", null, null, null, null, null);
        assertNotNull(g.excludedTools());
        assertNotNull(g.requiredTools());
        assertNotNull(g.dependencies());
        assertTrue(g.excludedTools().isEmpty());
        assertTrue(g.requiredTools().isEmpty());
        assertTrue(g.dependencies().isEmpty());
        assertEquals("", g.successCriteria());
    }

    @Test
    public void goal_ofFactory_allFields() {
        Budget b = Budget.unlimited();
        Goal g = Goal.of("g1", "parent", GoalStatus.ACTIVE, "desc",
                List.of("dep1"), b, "criteria",
                Set.of("excluded"), Set.of("required"));
        assertEquals("g1",        g.id());
        assertEquals("parent",    g.parentId());
        assertEquals("criteria",  g.successCriteria());
        assertEquals(Set.of("excluded"), g.excludedTools());
        assertEquals(Set.of("required"), g.requiredTools());
        assertEquals(List.of("dep1"),    g.dependencies());
    }

    @Test
    public void goal_withExcludedTools_immutableCopy() {
        Goal g  = pendingGoal("g");
        Goal g2 = g.withExcludedTools(Set.of("foo", "bar"));
        assertTrue(g.excludedTools().isEmpty(),       "original unchanged");
        assertTrue(g2.excludedTools().contains("foo"), "copy has excluded tool");
        // Returned set must be unmodifiable
        assertThrows(UnsupportedOperationException.class,
                () -> g2.excludedTools().add("baz"));
    }

    @Test
    public void goal_withRequiredTools_immutableCopy() {
        Goal g  = pendingGoal("g");
        Goal g2 = g.withRequiredTools(Set.of("must_use"));
        assertTrue(g.requiredTools().isEmpty(),           "original unchanged");
        assertTrue(g2.requiredTools().contains("must_use"), "copy has required tool");
        assertThrows(UnsupportedOperationException.class,
                () -> g2.requiredTools().add("other"));
    }

    @Test
    public void goal_withStatus_fluentCopy() {
        Goal g  = pendingGoal("g");
        Goal g2 = g.withStatus(GoalStatus.COMPLETED);
        assertEquals(GoalStatus.PENDING,   g.status(),  "original unchanged");
        assertEquals(GoalStatus.COMPLETED, g2.status(), "copy has new status");
    }
}
