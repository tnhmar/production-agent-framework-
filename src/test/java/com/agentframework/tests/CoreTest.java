package com.agentframework.tests;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.List;
public class CoreTest {

    private DefaultExecutionContext ctx() {
        Task t = Task.builder().instruction("test").build();
        return new DefaultExecutionContext(t, "tenant1", "user1");
    }

    @Test
    public void testExecutionContextDefaults() {
        DefaultExecutionContext c = ctx();
        assertNotNull(c.runId(), "runId not null");
        assertEquals("tenant1", c.tenantId(), "tenantId");
        assertEquals(RunState.INITIALIZED, c.currentState(), "initial state");
        assertEquals(0, c.cycleCount(), "initial cycle");
        assertEquals(BigDecimal.ZERO, c.totalCost(), "initial cost");
    }

    @Test
    public void testStateTransitions() {
        DefaultExecutionContext c = ctx();
        c.transitionTo(RunState.PLANNING);
        assertEquals(RunState.PLANNING, c.currentState(), "planning");
        c.transitionTo(RunState.COMPLETED);
        assertTrue(c.currentState().isTerminal(), "terminal");
    }

    @Test
    public void testCycleAndToken() {
        DefaultExecutionContext c = ctx();
        c.incrementCycle(); c.incrementCycle();
        assertEquals(2, c.cycleCount(), "2 cycles");
        c.addTokens(1000); c.addTokens(500);
        assertEquals(1500, c.totalTokensUsed(), "1500 tokens");
        c.addCost(BigDecimal.valueOf(5));
        assertEquals(BigDecimal.valueOf(5), c.totalCost(), "cost 5");
    }

    @Test
    public void testConsecutiveFailures() {
        DefaultExecutionContext c = ctx();
        c.incrementConsecutiveFailures();
        c.incrementConsecutiveFailures();
        assertEquals(2, c.consecutiveFailures(), "2 failures");
        c.resetConsecutiveFailures();
        assertEquals(0, c.consecutiveFailures(), "reset failures");
    }

    @Test
    public void testRevisionBudget() {
        DefaultExecutionContext c = ctx();
        c.incrementRevisionCount(); c.incrementRevisionCount(); c.incrementRevisionCount();
        assertFalse(c.isRevisionBudgetExceeded(3), "3 revisions not exceeded with max=3");
        c.incrementRevisionCount();
        assertTrue(c.isRevisionBudgetExceeded(3), "4 revisions exceeds max=3");
    }

    @Test
    public void testPlanStale() {
        DefaultExecutionContext c = ctx();
        assertFalse(c.isPlanStale(), "initially not stale");
        c.flagPlanStale("world changed");
        assertTrue(c.isPlanStale(), "stale after flag");
        assertEquals("world changed", c.stalenessHint(), "hint");
        c.flagPlanStale(null);
        assertFalse(c.isPlanStale(), "cleared");
    }

    @Test
    public void testCheckpoint() {
        DefaultExecutionContext c = ctx();
        c.transitionTo(RunState.PLANNING); c.incrementCycle();
        ExecutionContext.Snapshot snap = c.checkpoint();
        assertEquals(c.runId(), snap.runId(), "snapshot runId");
        assertEquals(RunState.PLANNING, snap.state(), "snapshot state");
        assertEquals(1, snap.cycle(), "snapshot cycle");
    }

    // ── GoalStack ─────────────────────────────────────────────
    @Test
    public void testGoalStackPushAndCurrent() {
        DefaultGoalStack gs = new DefaultGoalStack();
        assertFalse(gs.current().isPresent(), "empty");
        Goal g = new Goal("root", null, GoalStatus.PENDING, "do X", java.util.List.of(), Budget.unlimited());
        gs.push(g);
        assertTrue(gs.current().isPresent(), "has current");
        assertEquals("root", gs.current().get().id(), "current id");
    }

    @Test
    public void testGoalStackStatusUpdate() {
        DefaultGoalStack gs = new DefaultGoalStack();
        Goal g = new Goal("root", null, GoalStatus.PENDING, "do X", java.util.List.of(), Budget.unlimited());
        gs.push(g);
        gs.updateStatus("root", GoalStatus.COMPLETED);
        assertEquals(GoalStatus.COMPLETED, gs.current().get().status(), "status updated");
        assertTrue(gs.isRootAchieved(), "root achieved");
    }

    @Test
    public void testGoalStackDepth() {
        DefaultGoalStack gs = new DefaultGoalStack();
        for (int i=0;i<3;i++) gs.push(new Goal("g"+i, null, GoalStatus.PENDING, "d"+i, java.util.List.of(), null));
        assertEquals(3, gs.depth(), "depth 3");
        gs.pop();
        assertEquals(2, gs.depth(), "depth 2 after pop");
    }

    // ── BeliefState ───────────────────────────────────────────
    @Test
    public void testBeliefAssertAndRetrieve() {
        DefaultBeliefState bs = new DefaultBeliefState();
        Belief b = new Belief("b1", "sky", "color", "blue", 0.9, "observation", Instant.now(), false);
        bs.assertBelief(b);
        assertEquals(1, bs.size(), "size 1");
        assertTrue(bs.getBySPO("sky", "color").isPresent(), "found");
        assertEquals("blue", bs.getBySPO("sky","color").get().object(), "object");
    }

    @Test
    public void testBeliefConflictDetection() {
        DefaultBeliefState bs = new DefaultBeliefState();
        Belief b1 = new Belief("b1","sky","color","blue",  0.7,"obs1", Instant.now(),false);
        Belief b2 = new Belief("b2","sky","color","green", 0.9,"obs2", Instant.now(),false);
        bs.assertBelief(b1);
        bs.assertBelief(b2); // higher confidence, should win
        assertEquals(1, bs.conflicts().size(), "conflict recorded");
        assertEquals("green", bs.getBySPO("sky","color").get().object(), "higher conf wins");
    }

    @Test
    public void testBeliefRetract() {
        DefaultBeliefState bs = new DefaultBeliefState();
        Belief b = new Belief("b1","sky","color","blue", 0.9,"obs", Instant.now(),false);
        bs.assertBelief(b);
        bs.retract("b1");
        assertEquals(0, bs.size(), "empty after retract");
    }

    @Test
    public void testBeliefSameValueNoConflict() {
        DefaultBeliefState bs = new DefaultBeliefState();
        Belief b1 = new Belief("b1","sky","color","blue",0.7,"obs1",Instant.now(),false);
        Belief b2 = new Belief("b2","sky","color","blue",0.9,"obs2",Instant.now(),false);
        bs.assertBelief(b1); bs.assertBelief(b2);
        assertEquals(0, bs.conflicts().size(), "no conflict for same value");
    }

    // ── WorkingMemory ─────────────────────────────────────────
    @Test
    public void testWorkingMemoryAddAndGet() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        WorkingMemoryEntry e = new WorkingMemoryEntry("e1","hello",WorkingMemoryTier.ACTIVE,
            com.agentframework.foundation.Origin.USER,1.0,Instant.now(),
            com.agentframework.foundation.TaintLabel.CLEAN);
        wm.add(e);
        assertEquals(1, wm.size(), "size 1");
        assertEquals(1, wm.getAll().size(), "getAll size 1");
    }

    @Test
    public void testWorkingMemoryMarkProcessed() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        WorkingMemoryEntry e = new WorkingMemoryEntry("e1","hello",WorkingMemoryTier.ACTIVE,
            com.agentframework.foundation.Origin.USER,1.0,Instant.now(),
            com.agentframework.foundation.TaintLabel.CLEAN);
        wm.add(e);
        assertEquals(1, wm.getUnprocessed().size(), "1 unprocessed");
        wm.markProcessed("e1");
        assertEquals(0, wm.getUnprocessed().size(), "0 unprocessed after mark");
        assertTrue(wm.isProcessed("e1"), "isProcessed");
    }

    @Test
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
        assertEquals(3, wm.size(), "3 remain after evicting 2");
    }

    @Test
    public void testWorkingMemoryCompress() {
        DefaultWorkingMemory wm = new DefaultWorkingMemory();
        for (int i=0;i<3;i++) {
            wm.add(new WorkingMemoryEntry("e"+i,"data"+i,WorkingMemoryTier.ACTIVE,
                com.agentframework.foundation.Origin.TOOL,0.5,Instant.now(),
                com.agentframework.foundation.TaintLabel.CLEAN));
        }
        wm.compress(java.util.List.of("e0","e1","e2"), "compressed summary");
        assertEquals(1, wm.size(), "compressed to 1");
        assertEquals("compressed summary", wm.getAll().get(0).content(), "summary");
        assertEquals(WorkingMemoryTier.COMPRESSED, wm.getAll().get(0).tier(), "COMPRESSED tier");
    }

    @Test public void testGoalSuccessCriteria() {
        Budget b=new Budget(10,10_000,java.time.Duration.ofMinutes(1),BigDecimal.ONE);
        assertEquals("done !del",new Goal("g",null,GoalStatus.PENDING,"t",List.of(),b,"done !del").successCriteria());
        assertEquals("",new Goal("g",null,GoalStatus.PENDING,"t",List.of(),b).successCriteria());
        assertEquals("",new Goal("g",null,GoalStatus.PENDING,"t",List.of(),b,null).successCriteria());
        assertEquals("fluent",new Goal("g",null,GoalStatus.PENDING,"t",List.of(),b).withSuccessCriteria("fluent").successCriteria());
    }
    @Test public void testGoalCoherenceValidator() {
        Budget b=new Budget(10,10_000,java.time.Duration.ofMinutes(1),BigDecimal.ONE);
        GoalCoherencePlanValidator v=new GoalCoherencePlanValidator();
        assertInstanceOf(ValidationResult.Failed.class,v.validate(new ToolCall("x",Map.of(),""),ctx()));
        assertInstanceOf(ValidationResult.Passed.class,v.validate(new Escalate("h","HIGH"),ctx()));
        DefaultExecutionContext pend=ctx();
        pend.goalStack().push(new Goal("root",null,GoalStatus.PENDING,"t",List.of(),b));
        assertInstanceOf(ValidationResult.Passed.class,v.validate(new FinalAnswer("ok",List.of()),pend));
        DefaultExecutionContext comp=ctx();
        comp.goalStack().push(new Goal("root",null,GoalStatus.COMPLETED,"t",List.of(),b));
        assertInstanceOf(ValidationResult.Failed.class,v.validate(new FinalAnswer("x",List.of()),comp));
        DefaultExecutionContext excl=ctx();
        excl.goalStack().push(new Goal("root",null,GoalStatus.PENDING,"t",List.of(),b,"do !web_search"));
        assertInstanceOf(ValidationResult.NeedsCorrection.class,v.validate(new ToolCall("web_search",Map.of(),""),excl));
        assertInstanceOf(ValidationResult.Passed.class,v.validate(new ToolCall("calc",Map.of(),""),excl));
    }
    @Test public void testCompositePlanValidator() {
        CompositePlanValidator all=new CompositePlanValidator(List.of(new PassThroughPlanValidator(),new PassThroughPlanValidator()));
        assertInstanceOf(ValidationResult.Passed.class,all.validate(new FinalAnswer("ok",List.of()),ctx()));
        PlanValidator blk=new PlanValidator(){
            public ValidationResult validate(Decision d,ExecutionContext c){return new ValidationResult.Failed("b",List.of());}
            public ValidationResult validateAfterAction(ActionResult r,ExecutionContext c){return new ValidationResult.Passed();}};
        assertInstanceOf(ValidationResult.Failed.class,new CompositePlanValidator(List.of(blk,new PassThroughPlanValidator())).validate(new FinalAnswer("x",List.of()),ctx()));
        assertThrows(IllegalArgumentException.class,()->new CompositePlanValidator(List.of()));
    }
    @Test public void testLivenessCounters() {
        DefaultExecutionContext c=ctx();
        c.incrementStagnantCycles();c.incrementStagnantCycles();assertEquals(2,c.stagnantCycles());
        c.resetStagnantCycles();assertEquals(0,c.stagnantCycles());
        c.incrementStuckCycles();assertEquals(1,c.stuckCycles());c.resetStuckCycles();assertEquals(0,c.stuckCycles());
        c.incrementChainDepth();c.incrementChainDepth();assertEquals(2,c.currentChainDepth());
        c.resetChainDepth();assertEquals(0,c.currentChainDepth());
    }
    @Test public void testSnapshotIntegrity() {
        ExecutionContext.Snapshot s=ctx().checkpoint();
        assertNotNull(s.integrityHash());assertFalse(s.integrityHash().isBlank());
        assertEquals(s.integrityHash(),DefaultExecutionContext.computeSnapshotHash(s));
        assertEquals(DefaultExecutionContext.SNAPSHOT_SCHEMA_VERSION,s.schemaVersion());
    }
}
