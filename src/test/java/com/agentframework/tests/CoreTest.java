package com.agentframework.tests;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.testutil.Assert;
import java.math.BigDecimal;
import java.time.Instant;
public class CoreTest {

    private DefaultExecutionContext ctx() {
        Task t = Task.builder().instruction("test").build();
        return new DefaultExecutionContext(t, "tenant1", "user1");
    }

    public void testExecutionContextDefaults() {
        DefaultExecutionContext c = ctx();
        Assert.assertNotNull(c.runId(), "runId not null");
        Assert.assertEquals("tenant1", c.tenantId(), "tenantId");
        Assert.assertEquals(RunState.INITIALIZED, c.currentState(), "initial state");
        Assert.assertEquals(0, c.cycleCount(), "initial cycle");
        Assert.assertEquals(BigDecimal.ZERO, c.totalCost(), "initial cost");
    }

    public void testStateTransitions() {
        DefaultExecutionContext c = ctx();
        c.transitionTo(RunState.PLANNING);
        Assert.assertEquals(RunState.PLANNING, c.currentState(), "planning");
        c.transitionTo(RunState.COMPLETED);
        Assert.assertTrue(c.currentState().isTerminal(), "terminal");
    }

    public void testCycleAndToken() {
        DefaultExecutionContext c = ctx();
        c.incrementCycle(); c.incrementCycle();
        Assert.assertEquals(2, c.cycleCount(), "2 cycles");
        c.addTokens(1000); c.addTokens(500);
        Assert.assertEquals(1500, c.totalTokensUsed(), "1500 tokens");
        c.addCost(BigDecimal.valueOf(5));
        Assert.assertEquals(BigDecimal.valueOf(5), c.totalCost(), "cost 5");
    }

    public void testConsecutiveFailures() {
        DefaultExecutionContext c = ctx();
        c.incrementConsecutiveFailures();
        c.incrementConsecutiveFailures();
        Assert.assertEquals(2, c.consecutiveFailures(), "2 failures");
        c.resetConsecutiveFailures();
        Assert.assertEquals(0, c.consecutiveFailures(), "reset failures");
    }

    public void testRevisionBudget() {
        DefaultExecutionContext c = ctx();
        c.incrementRevisionCount(); c.incrementRevisionCount(); c.incrementRevisionCount();
        Assert.assertFalse(c.isRevisionBudgetExceeded(3), "3 revisions not exceeded with max=3");
        c.incrementRevisionCount();
        Assert.assertTrue(c.isRevisionBudgetExceeded(3), "4 revisions exceeds max=3");
    }

    public void testPlanStale() {
        DefaultExecutionContext c = ctx();
        Assert.assertFalse(c.isPlanStale(), "initially not stale");
        c.flagPlanStale("world changed");
        Assert.assertTrue(c.isPlanStale(), "stale after flag");
        Assert.assertEquals("world changed", c.stalenessHint(), "hint");
        c.flagPlanStale(null);
        Assert.assertFalse(c.isPlanStale(), "cleared");
    }

    public void testCheckpoint() {
        DefaultExecutionContext c = ctx();
        c.transitionTo(RunState.PLANNING); c.incrementCycle();
        ExecutionContext.Snapshot snap = c.checkpoint();
        Assert.assertEquals(c.runId(), snap.runId(), "snapshot runId");
        Assert.assertEquals(RunState.PLANNING, snap.state(), "snapshot state");
        Assert.assertEquals(1, snap.cycle(), "snapshot cycle");
    }

    // ── GoalStack ─────────────────────────────────────────────
    public void testGoalStackPushAndCurrent() {
        DefaultGoalStack gs = new DefaultGoalStack();
        Assert.assertFalse(gs.current().isPresent(), "empty");
        Goal g = new Goal("root", null, GoalStatus.PENDING, "do X", java.util.List.of(), Budget.unlimited());
        gs.push(g);
        Assert.assertTrue(gs.current().isPresent(), "has current");
        Assert.assertEquals("root", gs.current().get().id(), "current id");
    }

    public void testGoalStackStatusUpdate() {
        DefaultGoalStack gs = new DefaultGoalStack();
        Goal g = new Goal("root", null, GoalStatus.PENDING, "do X", java.util.List.of(), Budget.unlimited());
        gs.push(g);
        gs.updateStatus("root", GoalStatus.COMPLETED);
        Assert.assertEquals(GoalStatus.COMPLETED, gs.current().get().status(), "status updated");
        Assert.assertTrue(gs.isRootAchieved(), "root achieved");
    }

    public void testGoalStackDepth() {
        DefaultGoalStack gs = new DefaultGoalStack();
        for (int i=0;i<3;i++) gs.push(new Goal("g"+i, null, GoalStatus.PENDING, "d"+i, java.util.List.of(), null));
        Assert.assertEquals(3, gs.depth(), "depth 3");
        gs.pop();
        Assert.assertEquals(2, gs.depth(), "depth 2 after pop");
    }

    // ── BeliefState ───────────────────────────────────────────
    public void testBeliefAssertAndRetrieve() {
        DefaultBeliefState bs = new DefaultBeliefState();
        Belief b = new Belief("b1", "sky", "color", "blue", 0.9, "observation", Instant.now(), false);
        bs.assertBelief(b);
        Assert.assertEquals(1, bs.size(), "size 1");
        Assert.assertTrue(bs.getBySPO("sky", "color").isPresent(), "found");
        Assert.assertEquals("blue", bs.getBySPO("sky","color").get().object(), "object");
    }

    public void testBeliefConflictDetection() {
        DefaultBeliefState bs = new DefaultBeliefState();
        Belief b1 = new Belief("b1","sky","color","blue",  0.7,"obs1", Instant.now(),false);
        Belief b2 = new Belief("b2","sky","color","green", 0.9,"obs2", Instant.now(),false);
        bs.assertBelief(b1);
        bs.assertBelief(b2); // higher confidence, should win
        Assert.assertEquals(1, bs.conflicts().size(), "conflict recorded");
        Assert.assertEquals("green", bs.getBySPO("sky","color").get().object(), "higher conf wins");
    }

    public void testBeliefRetract() {
        DefaultBeliefState bs = new DefaultBeliefState();
        Belief b = new Belief("b1","sky","color","blue", 0.9,"obs", Instant.now(),false);
        bs.assertBelief(b);
        bs.retract("b1");
        Assert.assertEquals(0, bs.size(), "empty after retract");
    }

    public void testBeliefSameValueNoConflict() {
        DefaultBeliefState bs = new DefaultBeliefState();
        Belief b1 = new Belief("b1","sky","color","blue",0.7,"obs1",Instant.now(),false);
        Belief b2 = new Belief("b2","sky","color","blue",0.9,"obs2",Instant.now(),false);
        bs.assertBelief(b1); bs.assertBelief(b2);
        Assert.assertEquals(0, bs.conflicts().size(), "no conflict for same value");
    }

    // ── WorkingMemory ─────────────────────────────────────────
    public void testWorkingMemoryAddAndGet() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        WorkingMemoryEntry e = new WorkingMemoryEntry("e1","hello",WorkingMemoryTier.ACTIVE,
            com.agentframework.foundation.Origin.USER,1.0,Instant.now(),
            com.agentframework.foundation.TaintLabel.CLEAN);
        wm.add(e);
        Assert.assertEquals(1, wm.size(), "size 1");
        Assert.assertEquals(1, wm.getAll().size(), "getAll size 1");
    }

    public void testWorkingMemoryMarkProcessed() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        WorkingMemoryEntry e = new WorkingMemoryEntry("e1","hello",WorkingMemoryTier.ACTIVE,
            com.agentframework.foundation.Origin.USER,1.0,Instant.now(),
            com.agentframework.foundation.TaintLabel.CLEAN);
        wm.add(e);
        Assert.assertEquals(1, wm.getUnprocessed().size(), "1 unprocessed");
        wm.markProcessed("e1");
        Assert.assertEquals(0, wm.getUnprocessed().size(), "0 unprocessed after mark");
        Assert.assertTrue(wm.isProcessed("e1"), "isProcessed");
    }

    public void testWorkingMemoryEvict() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        for (int i=0;i<5;i++) {
            WorkingMemoryEntry e = new WorkingMemoryEntry("e"+i,"content"+i,
                WorkingMemoryTier.ACTIVE, com.agentframework.foundation.Origin.USER,
                (double)i, Instant.now().minusSeconds(5-i),
                com.agentframework.foundation.TaintLabel.CLEAN);
            wm.add(e);
        }
        wm.evictOldest(2);
        Assert.assertEquals(3, wm.size(), "3 remain after evicting 2");
    }

    public void testWorkingMemoryCompress() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        for (int i=0;i<3;i++) {
            wm.add(new WorkingMemoryEntry("e"+i,"data"+i,WorkingMemoryTier.ACTIVE,
                com.agentframework.foundation.Origin.TOOL,0.5,Instant.now(),
                com.agentframework.foundation.TaintLabel.CLEAN));
        }
        wm.compress(java.util.List.of("e0","e1","e2"), "compressed summary");
        Assert.assertEquals(1, wm.size(), "compressed to 1");
        Assert.assertEquals("compressed summary", wm.getAll().get(0).content(), "summary");
        Assert.assertEquals(WorkingMemoryTier.COMPRESSED, wm.getAll().get(0).tier(), "COMPRESSED tier");
    }
}
