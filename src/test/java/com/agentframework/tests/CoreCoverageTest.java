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
        assertEquals(h1, h2, "same input must produce same hash");
        assertNotEquals(h1, h3, "different goals must produce different hash");
    }

    @Test
    public void livenessDetector_checkStagnation_progressResetsCounter() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(3, 2);
        DefaultExecutionContext c  = ctx();
        c.incrementStagnantCycles();
        Optional<TerminationReason> r = ld.checkStagnation("aaa", "bbb",
                new FinalAnswer("ok", List.of()), c);
        assertTrue(r.isEmpty(), "progress must not terminate");
        assertEquals(0, c.stagnantCycles(), "counter must be reset on progress");
    }

    @Test
    public void livenessDetector_checkStagnation_sameHashAccumulates() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(3, 2);
        DefaultExecutionContext c  = ctx();
        FinalAnswer fa = new FinalAnswer("ok", List.of());
        ld.checkStagnation("X", "X", fa, c);
        ld.checkStagnation("X", "X", fa, c);
        assertEquals(2, c.stagnantCycles());
        assertTrue(ld.checkStagnation("X", "X", fa, c).isPresent(),
                "third identical hash must trigger StagnationLimit");
    }

    @Test
    public void livenessDetector_checkStagnation_nonSubstantiveDecisionSkipped() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(1, 1);
        DefaultExecutionContext c  = ctx();
        Optional<TerminationReason> r = ld.checkStagnation("X", "X",
                new AskClarification("clarify?"), c);
        assertTrue(r.isEmpty(), "non-substantive decision must be skipped");
        assertEquals(0, c.stagnantCycles(), "counter must not be incremented");
    }

    @Test
    public void livenessDetector_checkStuck_substantiveDecisionResetsCounter() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(3, 2);
        DefaultExecutionContext c  = ctx();
        c.incrementStuckCycles();
        Optional<TerminationReason> r = ld.checkStuck(new ToolCall("t", Map.of(), ""), c);
        assertTrue(r.isEmpty(), "substantive decision must not terminate");
        assertEquals(0, c.stuckCycles(), "stuck counter must be reset");
    }

    @Test
    public void livenessDetector_checkStuck_nonSubstantiveAccumulatesAndTerminates() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(3, 2);
        DefaultExecutionContext c  = ctx();
        AskClarification ask = new AskClarification("?");
        Optional<TerminationReason> r1 = ld.checkStuck(ask, c);
        assertTrue(r1.isEmpty(), "1 stuck cycle must not yet terminate");
        Optional<TerminationReason> r2 = ld.checkStuck(ask, c);
        assertTrue(r2.isPresent(), "2nd non-substantive cycle must trigger Escalated");
        assertInstanceOf(TerminationReason.Escalated.class, r2.get());
    }

    @Test
    public void livenessDetector_checkStuck_parallelToolCallsIsSubstantive() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(3, 2);
        DefaultExecutionContext c  = ctx();
        c.incrementStuckCycles();
        // ParallelToolCalls(List<ToolCall> calls, boolean requireAll, Duration deadline)
        ld.checkStuck(
            new ParallelToolCalls(
                List.of(new ToolCall("t", Map.of(), "")),
                true,
                Duration.ZERO),
            c);
        assertEquals(0, c.stuckCycles(), "ParallelToolCalls must be treated as substantive");
    }

    @Test
    public void livenessDetector_checkStuck_escalateIsSubstantive() {
        DefaultLivenessDetector ld = new DefaultLivenessDetector(3, 2);
        DefaultExecutionContext c  = ctx();
        c.incrementStuckCycles();
        ld.checkStuck(new Escalate("reason", "HIGH"), c);
        assertEquals(0, c.stuckCycles(), "Escalate must be treated as substantive");
    }

    // ─────────────────────────────────────────────────────────────────
    // DefaultBeliefState
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void beliefState_getBySPO_absent() {
        DefaultBeliefState bs = new DefaultBeliefState();
        assertTrue(bs.getBySPO("sky", "color").isEmpty());
    }

    @Test
    public void beliefState_allWithMinConfFilter() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "sky",  "color", "blue",  0.3));
        bs.assertBelief(belief("b2", "grass", "color", "green", 0.9));
        List<Belief> high = bs.all(0.5);
        assertEquals(1, high.size());
        assertEquals("green", high.get(0).object());
    }

    @Test
    public void beliefState_conflictLowerConfExistingWins() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "sky", "color", "blue",  0.9));
        bs.assertBelief(belief("b2", "sky", "color", "green", 0.3));
        assertEquals("blue", bs.getBySPO("sky", "color").get().object());
        assertEquals(1, bs.conflicts().size());
    }

    @Test
    public void beliefState_sameKeyHigherConfNoConflict_updatesEntry() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "sky", "color", "blue", 0.5));
        bs.assertBelief(belief("b2", "sky", "color", "blue", 0.8));
        assertEquals(0, bs.conflicts().size());
        assertEquals(0.8, bs.getBySPO("sky", "color").get().confidence(), 1e-9);
    }

    @Test
    public void beliefState_resolveConflict_clearsConflictedFlag() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "sky", "color", "blue",  0.5));
        bs.assertBelief(belief("b2", "sky", "color", "green", 0.9));
        Belief winner = bs.getBySPO("sky", "color").get();
        assertTrue(winner.conflicted());
        bs.resolveConflict("sky", "color", winner.beliefId());
        assertFalse(bs.getBySPO("sky", "color").get().conflicted());
    }

    @Test
    public void beliefState_resolveConflict_wrongIdIsNoop() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "sky", "color", "blue", 0.9));
        assertDoesNotThrow(() -> bs.resolveConflict("sky", "color", "wrong-id"));
    }

    @Test
    public void beliefState_resolveConflict_absentKeyIsNoop() {
        DefaultBeliefState bs = new DefaultBeliefState();
        assertDoesNotThrow(() -> bs.resolveConflict("ghost", "predicate", "any"));
    }

    // ─────────────────────────────────────────────────────────────────
    // DefaultGoalStack
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void goalStack_allActive_filtersCompleted() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(pendingGoal("g1"));
        gs.push(pendingGoal("g2"));
        gs.updateStatus("g1", GoalStatus.COMPLETED);
        List<Goal> active = gs.allActive();
        assertEquals(1, active.size());
        assertEquals("g2", active.get(0).id());
    }

    @Test
    public void goalStack_updateStatus_nonTopGoal() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(pendingGoal("g1"));
        gs.push(pendingGoal("g2"));
        gs.updateStatus("g1", GoalStatus.FAILED);
        assertEquals(GoalStatus.FAILED,
                gs.all().stream().filter(g -> g.id().equals("g1")).findFirst().get().status());
        assertEquals(GoalStatus.PENDING, gs.current().get().status());
    }

    @Test
    public void goalStack_updateStatus_unknownIdIsNoop() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(pendingGoal("g1"));
        assertDoesNotThrow(() -> gs.updateStatus("ghost", GoalStatus.COMPLETED));
        assertEquals(GoalStatus.PENDING, gs.current().get().status());
    }

    @Test
    public void goalStack_isRootAchieved_falseWhenAbsent() {
        DefaultGoalStack gs = new DefaultGoalStack();
        assertFalse(gs.isRootAchieved());
    }

    @Test
    public void goalStack_isRootAchieved_falseWhenPending() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(pendingGoal("root"));
        assertFalse(gs.isRootAchieved());
    }

    @Test
    public void goalStack_popEmpty_noThrow() {
        DefaultGoalStack gs = new DefaultGoalStack();
        assertDoesNotThrow(gs::pop);
    }

    @Test
    public void goalStack_all_returnsSnapshot() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(pendingGoal("a"));
        gs.push(pendingGoal("b"));
        List<Goal> all = gs.all();
        assertEquals(2, all.size());
        all.clear();
        assertEquals(2, gs.all().size(), "returned snapshot must be independent");
    }

    // ─────────────────────────────────────────────────────────────────
    // DefaultWorkingMemory
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void workingMemory_getByOrigin() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(wme("t1", WorkingMemoryTier.ACTIVE,     Origin.TOOL,   0.9));
        wm.add(wme("u1", WorkingMemoryTier.ACTIVE,     Origin.USER,   0.8));
        wm.add(wme("t2", WorkingMemoryTier.BACKGROUND, Origin.TOOL,   0.5));
        List<WorkingMemoryEntry> toolEntries = wm.getByOrigin(Origin.TOOL);
        assertEquals(2, toolEntries.size());
        assertTrue(wm.getByOrigin(Origin.SYSTEM).isEmpty());
    }

    @Test
    public void workingMemory_evictLowestRelevance_tierAwareOrdering() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(wme("arch",   WorkingMemoryTier.ARCHIVED, Origin.SYSTEM, 1.0));
        wm.add(wme("active", WorkingMemoryTier.ACTIVE,   Origin.TOOL,   0.1));
        wm.evictLowestRelevance(1);
        assertEquals(1, wm.size());
        assertEquals("active", wm.getAll().get(0).id(),
                "ARCHIVED must be evicted before ACTIVE despite higher score");
    }

    @Test
    public void workingMemory_evictLowestRelevance_withinSameTierByScore() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(wme("hi",  WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.9));
        wm.add(wme("lo",  WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.1));
        wm.add(wme("mid", WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5));
        wm.evictLowestRelevance(1);
        assertEquals(2, wm.size());
        assertTrue(wm.getAll().stream().noneMatch(e -> e.id().equals("lo")),
                "lowest-score entry must be evicted");
    }

    @Test
    public void workingMemory_evictMore_clampsToSize() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(wme("e1", WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5));
        wm.add(wme("e2", WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5));
        assertDoesNotThrow(() -> wm.evictOldest(100));
        assertEquals(0, wm.size());
    }

    @Test
    public void workingMemory_evictCleansProcessedSet() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(wme("e1", WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5));
        wm.markProcessed("e1");
        assertTrue(wm.isProcessed("e1"));
        wm.evictOldest(1);
        assertFalse(wm.isProcessed("e1"), "eviction must clean ghost reference from processed set");
    }

    @Test
    public void workingMemory_estimatedTokenCount() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
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
        assertEquals(0, wm.size());
        assertFalse(wm.isProcessed("e1"));
    }

    @Test
    public void workingMemory_compressCleansGhostRefs() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        wm.add(wme("e1", WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.5));
        wm.markProcessed("e1");
        wm.compress(List.of("e1"), "summary");
        assertFalse(wm.isProcessed("e1"), "compress must clean ghost reference from processed set");
    }

    // ─────────────────────────────────────────────────────────────────
    // DefaultExecutionContext
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void executionContext_terminationReason_initiallyEmpty() {
        assertTrue(ctx().terminationReason().isEmpty());
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
        assertTrue(c.activeJobs().isEmpty());
        // JobToken(String jobId, String statusEndpoint, Duration estimatedDuration)
        JobToken tok = new JobToken("job-1", "/status/job-1", Duration.ofSeconds(5));
        c.activeJobs().put("job-1", tok);
        assertEquals(1, c.activeJobs().size());
        assertSame(tok, c.activeJobs().get("job-1"));
    }

    @Test
    public void executionContext_recordCycleAndTrace() {
        DefaultExecutionContext c = ctx();
        assertEquals(0, c.trace().size());
        // ActionResult.Escalated(String reason, String level)
        CycleRecord rec = CycleRecord.of(
                0,
                new Observations(List.of()),
                new FinalAnswer("ok", List.of()),
                new ActionResult.Escalated("none", "HIGH"),
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

        DefaultExecutionContext restored =
                new DefaultExecutionContext(Task.builder().instruction("r").build(), "t", "u");
        restored.restoreFromSnapshot(snap);

        assertEquals(snap.cycle(),               restored.cycleCount());
        assertEquals(snap.state(),               restored.currentState());
        assertEquals(snap.totalTokens(),         restored.totalTokensUsed());
        assertEquals(snap.totalCost(),           restored.totalCost());
        assertEquals(snap.consecutiveFailures(), restored.consecutiveFailures());
        assertEquals(snap.stagnantCycles(),      restored.stagnantCycles());
        assertEquals(snap.stuckCycles(),         restored.stuckCycles());
        assertEquals(snap.revisionCount(),       restored.revisionCount());
        assertFalse(restored.goalStack().all().isEmpty());
        assertFalse(restored.workingMemory().getAll().isEmpty());
        assertFalse(restored.beliefState().all(0.0).isEmpty());
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
        assertEquals(snap.integrityHash(),
                DefaultExecutionContext.computeSnapshotHash(snap));
    }

    // ─────────────────────────────────────────────────────────────────
    // Goal
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
        assertEquals("g1",             g.id());
        assertEquals("parent",         g.parentId());
        assertEquals("criteria",       g.successCriteria());
        assertEquals(Set.of("excluded"), g.excludedTools());
        assertEquals(Set.of("required"), g.requiredTools());
        assertEquals(List.of("dep1"),    g.dependencies());
    }

    @Test
    public void goal_withExcludedTools_immutableCopy() {
        Goal g  = pendingGoal("g");
        Goal g2 = g.withExcludedTools(Set.of("foo", "bar"));
        assertTrue(g.excludedTools().isEmpty());
        assertTrue(g2.excludedTools().contains("foo"));
        assertThrows(UnsupportedOperationException.class,
                () -> g2.excludedTools().add("baz"));
    }

    @Test
    public void goal_withRequiredTools_immutableCopy() {
        Goal g  = pendingGoal("g");
        Goal g2 = g.withRequiredTools(Set.of("must_use"));
        assertTrue(g.requiredTools().isEmpty());
        assertTrue(g2.requiredTools().contains("must_use"));
        assertThrows(UnsupportedOperationException.class,
                () -> g2.requiredTools().add("other"));
    }

    @Test
    public void goal_withStatus_fluentCopy() {
        Goal g  = pendingGoal("g");
        Goal g2 = g.withStatus(GoalStatus.COMPLETED);
        assertEquals(GoalStatus.PENDING,   g.status());
        assertEquals(GoalStatus.COMPLETED, g2.status());
    }
}
