package com.agentframework.tests;

import com.agentframework.core.*;
import com.agentframework.foundation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public class PlanValidatorTest {

    // ───────────────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────────────

    private static Belief belief(String id, String subj, String pred, String obj, double conf) {
        return new Belief(id, subj, pred, obj, conf, "test", Instant.now(), false);
    }

    private static Goal rootGoal(GoalStatus status) {
        return new Goal("root", null, status, "root goal", List.of(), Budget.unlimited());
    }

    private static Goal rootGoalWithConstraints(GoalStatus status,
                                                 Set<String> excluded, Set<String> required) {
        return Goal.of("root", null, status, "constrained root", List.of(),
                Budget.unlimited(), "", excluded, required);
    }

    private DefaultExecutionContext ctxWithGoal(Goal g) {
        Task t = Task.builder().instruction("test").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(t, "t1", "u1");
        ctx.goalStack().push(g);
        return ctx;
    }

    private DefaultExecutionContext emptyCtx() {
        Task t = Task.builder().instruction("test").build();
        return new DefaultExecutionContext(t, "t1", "u1");
    }

    // ───────────────────────────────────────────────────────────────────────
    // DefaultBeliefState
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void belief_assertNew_stored() {
        DefaultBeliefState bs = new DefaultBeliefState();
        Belief b = belief("b1", "agent", "knows", "java", 0.9);
        bs.assertBelief(b);
        assertTrue(bs.getBySPO("agent", "knows").isPresent());
        assertEquals("java", bs.getBySPO("agent", "knows").get().object());
    }

    @Test
    public void belief_assertNull_throws() {
        DefaultBeliefState bs = new DefaultBeliefState();
        assertThrows(NullPointerException.class, () -> bs.assertBelief(null));
    }

    @Test
    public void belief_higherConfidenceWins_noConflict() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "agent", "status", "idle", 0.5));
        bs.assertBelief(belief("b2", "agent", "status", "idle", 0.9));
        assertEquals(0, bs.conflicts().size(), "same object -> no conflict logged");
        assertEquals(0.9, bs.getBySPO("agent", "status").get().confidence(), 1e-9);
    }

    @Test
    public void belief_conflictingObjectsLogged() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "agent", "status", "idle", 0.5));
        bs.assertBelief(belief("b2", "agent", "status", "busy", 0.9));
        assertEquals(1, bs.conflicts().size(), "one conflict logged");
        assertTrue(bs.getBySPO("agent", "status").get().conflicted());
    }

    @Test
    public void belief_retract_removesEntry() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "agent", "lang", "java", 0.8));
        assertEquals(1, bs.size());
        bs.retract("b1");
        assertEquals(0, bs.size());
        assertTrue(bs.getBySPO("agent", "lang").isEmpty());
    }

    @Test
    public void belief_allFiltersByMinConf() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "agent", "p1", "v1", 0.3));
        bs.assertBelief(belief("b2", "agent", "p2", "v2", 0.8));
        List<Belief> above05 = bs.all(0.5);
        assertEquals(1, above05.size());
        assertEquals("p2", above05.get(0).predicate());
    }

    @Test
    public void belief_resolveConflict_clearsFlag() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "a", "p", "old", 0.5));
        Belief winner = bs.assertBelief(belief("b2", "a", "p", "new", 0.9));
        bs.resolveConflict("a", "p", winner.beliefId());
        assertFalse(bs.getBySPO("a", "p").get().conflicted(), "conflict cleared after resolution");
    }

    // ───────────────────────────────────────────────────────────────────────
    // DefaultGoalStack
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void goalStack_pushAndCurrent() {
        DefaultGoalStack gs = new DefaultGoalStack();
        Goal g = rootGoal(GoalStatus.ACTIVE);
        gs.push(g);
        assertTrue(gs.current().isPresent());
        assertEquals("root", gs.current().get().id());
        assertEquals(1, gs.depth());
    }

    @Test
    public void goalStack_emptyStackCurrentEmpty() {
        DefaultGoalStack gs = new DefaultGoalStack();
        assertTrue(gs.current().isEmpty());
        assertEquals(0, gs.depth());
    }

    @Test
    public void goalStack_popReducesDepth() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(rootGoal(GoalStatus.ACTIVE));
        gs.pop();
        assertEquals(0, gs.depth());
        assertTrue(gs.current().isEmpty());
    }

    @Test
    public void goalStack_updateStatusReflectsInCurrentAndAll() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(rootGoal(GoalStatus.PENDING));
        gs.updateStatus("root", GoalStatus.ACTIVE);
        assertEquals(GoalStatus.ACTIVE, gs.current().get().status());
        assertEquals(GoalStatus.ACTIVE, gs.all().get(0).status());
    }

    @Test
    public void goalStack_isRootAchieved_trueWhenCompleted() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(rootGoal(GoalStatus.ACTIVE));
        assertFalse(gs.isRootAchieved());
        gs.updateStatus("root", GoalStatus.COMPLETED);
        assertTrue(gs.isRootAchieved());
    }

    @Test
    public void goalStack_allActiveFiltersCorrectly() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(rootGoal(GoalStatus.ACTIVE));
        Goal sub = new Goal("sub", "root", GoalStatus.COMPLETED,
                "sub-goal", List.of(), Budget.unlimited());
        gs.push(sub);
        List<Goal> active = gs.allActive();
        assertEquals(1, active.size());
        assertEquals("root", active.get(0).id());
    }

    // ───────────────────────────────────────────────────────────────────────
    // Goal record
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void goal_blankId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new Goal("", null, GoalStatus.ACTIVE, "desc", List.of(), Budget.unlimited()));
    }

    @Test
    public void goal_nullId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new Goal(null, null, GoalStatus.ACTIVE, "desc", List.of(), Budget.unlimited()));
    }

    @Test
    public void goal_withStatus_returnsNewInstance() {
        Goal g = rootGoal(GoalStatus.PENDING);
        Goal updated = g.withStatus(GoalStatus.ACTIVE);
        assertNotSame(g, updated);
        assertEquals(GoalStatus.ACTIVE, updated.status());
        assertEquals(GoalStatus.PENDING, g.status(), "original unchanged");
    }

    @Test
    public void goal_withExcludedTools_immutable() {
        Goal g = rootGoal(GoalStatus.ACTIVE);
        Goal g2 = g.withExcludedTools(Set.of("badTool"));
        assertTrue(g2.excludedTools().contains("badTool"));
        assertTrue(g.excludedTools().isEmpty(), "original unaffected");
    }

    @Test
    public void goal_withRequiredTools_immutable() {
        Goal g = rootGoal(GoalStatus.ACTIVE);
        Goal g2 = g.withRequiredTools(Set.of("searchTool"));
        assertTrue(g2.requiredTools().contains("searchTool"));
        assertTrue(g.requiredTools().isEmpty(), "original unaffected");
    }

    // ───────────────────────────────────────────────────────────────────────
    // GoalCoherencePlanValidator
    // ───────────────────────────────────────────────────────────────────────

    private final GoalCoherencePlanValidator validator = new GoalCoherencePlanValidator();

    @Test
    public void coherence_escalate_alwaysPasses() {
        Escalate e = new Escalate("help");
        assertInstanceOf(ValidationResult.Passed.class,
                validator.validate(e, emptyCtx()));
    }

    @Test
    public void coherence_askClarification_alwaysPasses() {
        AskClarification a = new AskClarification("which format?");
        assertInstanceOf(ValidationResult.Passed.class,
                validator.validate(a, emptyCtx()));
    }

    @Test
    public void coherence_emptyGoalStack_fails() {
        FinalAnswer fa = new FinalAnswer("answer", "trace");
        ValidationResult r = validator.validate(fa, emptyCtx());
        assertInstanceOf(ValidationResult.Failed.class, r);
        assertTrue(((ValidationResult.Failed) r).reason().contains("No root goal"));
    }

    @Test
    public void coherence_noRootIdInStack_fails() {
        Task t = Task.builder().instruction("x").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(t, "t1", "u1");
        ctx.goalStack().push(new Goal("sub1", null, GoalStatus.ACTIVE,
                "sub", List.of(), Budget.unlimited()));
        ValidationResult r = validator.validate(new FinalAnswer("x", "t"), ctx);
        assertInstanceOf(ValidationResult.Failed.class, r);
        assertTrue(((ValidationResult.Failed) r).reason().contains("'root'"));
    }

    @Test
    public void coherence_finalAnswer_on_completedRoot_fails() {
        ValidationResult r = validator.validate(
                new FinalAnswer("dup", "t"),
                ctxWithGoal(rootGoal(GoalStatus.COMPLETED)));
        assertInstanceOf(ValidationResult.Failed.class, r);
        assertTrue(((ValidationResult.Failed) r).reason().contains("COMPLETED"));
    }

    @Test
    public void coherence_finalAnswer_on_failedRoot_fails() {
        ValidationResult r = validator.validate(
                new FinalAnswer("too late", "t"),
                ctxWithGoal(rootGoal(GoalStatus.FAILED)));
        assertInstanceOf(ValidationResult.Failed.class, r);
        assertTrue(((ValidationResult.Failed) r).reason().contains("FAILED"));
    }

    @Test
    public void coherence_finalAnswer_on_activeRoot_passes() {
        ValidationResult r = validator.validate(
                new FinalAnswer("ok", "t"),
                ctxWithGoal(rootGoal(GoalStatus.ACTIVE)));
        assertInstanceOf(ValidationResult.Passed.class, r);
    }

    @Test
    public void coherence_toolCall_excluded_needsCorrection() {
        Goal g = rootGoalWithConstraints(GoalStatus.ACTIVE, Set.of("dangerTool"), Set.of());
        ToolCall tc = new ToolCall("dangerTool", java.util.Map.of(), "trace");
        ValidationResult r = validator.validate(tc, ctxWithGoal(g));
        assertInstanceOf(ValidationResult.NeedsCorrection.class, r);
        assertTrue(((ValidationResult.NeedsCorrection) r).reason().contains("excluded"));
    }

    @Test
    public void coherence_toolCall_notInRequiredWhitelist_needsCorrection() {
        Goal g = rootGoalWithConstraints(GoalStatus.ACTIVE, Set.of(), Set.of("allowedTool"));
        ToolCall tc = new ToolCall("otherTool", java.util.Map.of(), "trace");
        ValidationResult r = validator.validate(tc, ctxWithGoal(g));
        assertInstanceOf(ValidationResult.NeedsCorrection.class, r);
        assertTrue(((ValidationResult.NeedsCorrection) r).reason().contains("whitelist"));
    }

    @Test
    public void coherence_toolCall_inRequiredWhitelist_passes() {
        Goal g = rootGoalWithConstraints(GoalStatus.ACTIVE, Set.of(), Set.of("allowedTool"));
        ToolCall tc = new ToolCall("allowedTool", java.util.Map.of(), "trace");
        assertInstanceOf(ValidationResult.Passed.class, validator.validate(tc, ctxWithGoal(g)));
    }

    @Test
    public void coherence_toolCall_noConstraints_passes() {
        ToolCall tc = new ToolCall("anyTool", java.util.Map.of(), "trace");
        assertInstanceOf(ValidationResult.Passed.class,
                validator.validate(tc, ctxWithGoal(rootGoal(GoalStatus.ACTIVE))));
    }

    @Test
    public void coherence_validateAfterAction_failedRoot_needsCorrection() {
        DefaultExecutionContext ctx = ctxWithGoal(rootGoal(GoalStatus.ACTIVE));
        ctx.goalStack().updateStatus("root", GoalStatus.FAILED);
        ActionResult ar = new ActionResult("search", "{}", true, 0);
        assertInstanceOf(ValidationResult.NeedsCorrection.class,
                validator.validateAfterAction(ar, ctx));
    }

    @Test
    public void coherence_validateAfterAction_noRoot_passes() {
        ActionResult ar = new ActionResult("search", "{}", true, 0);
        assertInstanceOf(ValidationResult.Passed.class,
                validator.validateAfterAction(ar, emptyCtx()));
    }

    // ───────────────────────────────────────────────────────────────────────
    // CompositePlanValidator
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void composite_allPass_returnsPass() {
        PlanValidator v1 = (d, c) -> new ValidationResult.Passed();
        PlanValidator v2 = (d, c) -> new ValidationResult.Passed();
        CompositePlanValidator comp = new CompositePlanValidator(List.of(v1, v2));
        assertInstanceOf(ValidationResult.Passed.class,
                comp.validate(new FinalAnswer("ok", "t"),
                        ctxWithGoal(rootGoal(GoalStatus.ACTIVE))));
    }

    @Test
    public void composite_firstFails_shortCircuits() {
        PlanValidator v1 = (d, c) -> new ValidationResult.Failed("v1 fail", List.of());
        PlanValidator v2 = (d, c) -> new ValidationResult.Passed();
        CompositePlanValidator comp = new CompositePlanValidator(List.of(v1, v2));
        ValidationResult r = comp.validate(new FinalAnswer("x", "t"),
                ctxWithGoal(rootGoal(GoalStatus.ACTIVE)));
        assertInstanceOf(ValidationResult.Failed.class, r);
        assertEquals("v1 fail", ((ValidationResult.Failed) r).reason());
    }

    @Test
    public void composite_emptyValidators_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompositePlanValidator(List.of()));
    }

    @Test
    public void composite_nullValidators_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompositePlanValidator(null));
    }

    @Test
    public void composite_validateAfterAction_shortCircuits() {
        PlanValidator afterV = new PlanValidator() {
            public ValidationResult validate(Decision d, ExecutionContext c) {
                return new ValidationResult.Passed();
            }
            public ValidationResult validateAfterAction(ActionResult r, ExecutionContext c) {
                return new ValidationResult.NeedsCorrection("after fail", null);
            }
        };
        PlanValidator noop = (d, c) -> new ValidationResult.Passed();
        CompositePlanValidator comp = new CompositePlanValidator(List.of(afterV, noop));
        ValidationResult r = comp.validateAfterAction(
                new ActionResult("t", "{}", true, 0), emptyCtx());
        assertInstanceOf(ValidationResult.NeedsCorrection.class, r);
    }
}
