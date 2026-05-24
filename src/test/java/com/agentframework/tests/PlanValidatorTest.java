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

    /** A PlanValidator that always passes both methods. */
    private static PlanValidator alwaysPass() {
        return new PlanValidator() {
            public ValidationResult validate(Decision d, ExecutionContext c) {
                return new ValidationResult.Passed();
            }
            public ValidationResult validateAfterAction(ActionResult r, ExecutionContext c) {
                return new ValidationResult.Passed();
            }
        };
    }

    /** A PlanValidator whose validate() always fails with the given reason. */
    private static PlanValidator alwaysFail(String reason) {
        return new PlanValidator() {
            public ValidationResult validate(Decision d, ExecutionContext c) {
                return new ValidationResult.Failed(reason, List.of());
            }
            public ValidationResult validateAfterAction(ActionResult r, ExecutionContext c) {
                return new ValidationResult.Passed();
            }
        };
    }

    /** A PlanValidator whose validateAfterAction() always returns NeedsCorrection. */
    private static PlanValidator afterActionFail(String reason) {
        return new PlanValidator() {
            public ValidationResult validate(Decision d, ExecutionContext c) {
                return new ValidationResult.Passed();
            }
            public ValidationResult validateAfterAction(ActionResult r, ExecutionContext c) {
                return new ValidationResult.NeedsCorrection(reason, null);
            }
        };
    }

    private DefaultExecutionContext ctxWithGoal(Goal g) {
        DefaultExecutionContext ctx = new DefaultExecutionContext(
                Task.builder().instruction("test").build(), "t1", "u1");
        ctx.goalStack().push(g);
        return ctx;
    }

    private DefaultExecutionContext emptyCtx() {
        return new DefaultExecutionContext(
                Task.builder().instruction("test").build(), "t1", "u1");
    }

    /** FinalAnswer with no citations. */
    private static FinalAnswer fa(String content) {
        return new FinalAnswer(content, List.of());
    }

    // ───────────────────────────────────────────────────────────────────────
    // DefaultBeliefState
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void belief_assertNew_stored() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "agent", "knows", "java", 0.9));
        assertTrue(bs.getBySPO("agent", "knows").isPresent());
        assertEquals("java", bs.getBySPO("agent", "knows").get().object());
    }

    @Test
    public void belief_assertNull_throws() {
        assertThrows(NullPointerException.class,
                () -> new DefaultBeliefState().assertBelief(null));
    }

    @Test
    public void belief_higherConfidenceWins_noConflict() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "agent", "status", "idle", 0.5));
        bs.assertBelief(belief("b2", "agent", "status", "idle", 0.9)); // same object -> no conflict
        assertEquals(0, bs.conflicts().size());
        assertEquals(0.9, bs.getBySPO("agent", "status").get().confidence(), 1e-9);
    }

    @Test
    public void belief_conflictingObjectsLogged() {
        DefaultBeliefState bs = new DefaultBeliefState();
        bs.assertBelief(belief("b1", "agent", "status", "idle", 0.5));
        bs.assertBelief(belief("b2", "agent", "status", "busy", 0.9)); // different object -> conflict
        assertEquals(1, bs.conflicts().size());
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
        assertFalse(bs.getBySPO("a", "p").get().conflicted());
    }

    // ───────────────────────────────────────────────────────────────────────
    // DefaultGoalStack
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void goalStack_pushAndCurrent() {
        DefaultGoalStack gs = new DefaultGoalStack();
        gs.push(rootGoal(GoalStatus.ACTIVE));
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
        gs.push(new Goal("sub", "root", GoalStatus.COMPLETED,
                "sub-goal", List.of(), Budget.unlimited()));
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
        assertEquals(GoalStatus.PENDING, g.status());
    }

    @Test
    public void goal_withExcludedTools_immutable() {
        Goal g = rootGoal(GoalStatus.ACTIVE);
        Goal g2 = g.withExcludedTools(Set.of("badTool"));
        assertTrue(g2.excludedTools().contains("badTool"));
        assertTrue(g.excludedTools().isEmpty());
    }

    @Test
    public void goal_withRequiredTools_immutable() {
        Goal g = rootGoal(GoalStatus.ACTIVE);
        Goal g2 = g.withRequiredTools(Set.of("searchTool"));
        assertTrue(g2.requiredTools().contains("searchTool"));
        assertTrue(g.requiredTools().isEmpty());
    }

    // ───────────────────────────────────────────────────────────────────────
    // GoalCoherencePlanValidator
    // ───────────────────────────────────────────────────────────────────────

    private final GoalCoherencePlanValidator validator = new GoalCoherencePlanValidator();

    @Test
    public void coherence_escalate_alwaysPasses() {
        // Escalate(reason, severity) - 2 args
        assertInstanceOf(ValidationResult.Passed.class,
                validator.validate(new Escalate("help", "HIGH"), emptyCtx()));
    }

    @Test
    public void coherence_askClarification_alwaysPasses() {
        assertInstanceOf(ValidationResult.Passed.class,
                validator.validate(new AskClarification("which format?"), emptyCtx()));
    }

    @Test
    public void coherence_emptyGoalStack_fails() {
        ValidationResult r = validator.validate(fa("answer"), emptyCtx());
        assertInstanceOf(ValidationResult.Failed.class, r);
        assertTrue(((ValidationResult.Failed) r).reason().contains("No root goal"));
    }

    @Test
    public void coherence_noRootIdInStack_fails() {
        DefaultExecutionContext ctx = new DefaultExecutionContext(
                Task.builder().instruction("x").build(), "t1", "u1");
        ctx.goalStack().push(new Goal("sub1", null, GoalStatus.ACTIVE,
                "sub", List.of(), Budget.unlimited()));
        ValidationResult r = validator.validate(fa("x"), ctx);
        assertInstanceOf(ValidationResult.Failed.class, r);
        assertTrue(((ValidationResult.Failed) r).reason().contains("'root'"));
    }

    @Test
    public void coherence_finalAnswer_on_completedRoot_fails() {
        ValidationResult r = validator.validate(fa("dup"),
                ctxWithGoal(rootGoal(GoalStatus.COMPLETED)));
        assertInstanceOf(ValidationResult.Failed.class, r);
        assertTrue(((ValidationResult.Failed) r).reason().contains("COMPLETED"));
    }

    @Test
    public void coherence_finalAnswer_on_failedRoot_fails() {
        ValidationResult r = validator.validate(fa("too late"),
                ctxWithGoal(rootGoal(GoalStatus.FAILED)));
        assertInstanceOf(ValidationResult.Failed.class, r);
        assertTrue(((ValidationResult.Failed) r).reason().contains("FAILED"));
    }

    @Test
    public void coherence_finalAnswer_on_activeRoot_passes() {
        assertInstanceOf(ValidationResult.Passed.class,
                validator.validate(fa("ok"), ctxWithGoal(rootGoal(GoalStatus.ACTIVE))));
    }

    @Test
    public void coherence_toolCall_excluded_needsCorrection() {
        Goal g = rootGoalWithConstraints(GoalStatus.ACTIVE, Set.of("dangerTool"), Set.of());
        ValidationResult r = validator.validate(
                new ToolCall("dangerTool", java.util.Map.of(), "trace"), ctxWithGoal(g));
        assertInstanceOf(ValidationResult.NeedsCorrection.class, r);
        assertTrue(((ValidationResult.NeedsCorrection) r).reason().contains("excluded"));
    }

    @Test
    public void coherence_toolCall_notInRequiredWhitelist_needsCorrection() {
        Goal g = rootGoalWithConstraints(GoalStatus.ACTIVE, Set.of(), Set.of("allowedTool"));
        ValidationResult r = validator.validate(
                new ToolCall("otherTool", java.util.Map.of(), "trace"), ctxWithGoal(g));
        assertInstanceOf(ValidationResult.NeedsCorrection.class, r);
        assertTrue(((ValidationResult.NeedsCorrection) r).reason().contains("whitelist"));
    }

    @Test
    public void coherence_toolCall_inRequiredWhitelist_passes() {
        Goal g = rootGoalWithConstraints(GoalStatus.ACTIVE, Set.of(), Set.of("allowedTool"));
        assertInstanceOf(ValidationResult.Passed.class,
                validator.validate(new ToolCall("allowedTool", java.util.Map.of(), "trace"),
                        ctxWithGoal(g)));
    }

    @Test
    public void coherence_toolCall_noConstraints_passes() {
        assertInstanceOf(ValidationResult.Passed.class,
                validator.validate(new ToolCall("anyTool", java.util.Map.of(), "trace"),
                        ctxWithGoal(rootGoal(GoalStatus.ACTIVE))));
    }

    @Test
    public void coherence_validateAfterAction_failedRoot_needsCorrection() {
        DefaultExecutionContext ctx = ctxWithGoal(rootGoal(GoalStatus.ACTIVE));
        ctx.goalStack().updateStatus("root", GoalStatus.FAILED);
        assertInstanceOf(ValidationResult.NeedsCorrection.class,
                validator.validateAfterAction(ActionResult.failure("ERR", "fail"), ctx));
    }

    @Test
    public void coherence_validateAfterAction_noRoot_passes() {
        assertInstanceOf(ValidationResult.Passed.class,
                validator.validateAfterAction(ActionResult.failure("ERR", "x"), emptyCtx()));
    }

    // ───────────────────────────────────────────────────────────────────────
    // CompositePlanValidator
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void composite_allPass_returnsPass() {
        CompositePlanValidator comp = new CompositePlanValidator(
                List.of(alwaysPass(), alwaysPass()));
        assertInstanceOf(ValidationResult.Passed.class,
                comp.validate(fa("ok"), ctxWithGoal(rootGoal(GoalStatus.ACTIVE))));
    }

    @Test
    public void composite_firstFails_shortCircuits() {
        CompositePlanValidator comp = new CompositePlanValidator(
                List.of(alwaysFail("v1 fail"), alwaysPass()));
        ValidationResult r = comp.validate(fa("x"),
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
        CompositePlanValidator comp = new CompositePlanValidator(
                List.of(afterActionFail("after fail"), alwaysPass()));
        ValidationResult r = comp.validateAfterAction(
                ActionResult.failure("ERR", "x"), emptyCtx());
        assertInstanceOf(ValidationResult.NeedsCorrection.class, r);
        assertEquals("after fail", ((ValidationResult.NeedsCorrection) r).reason());
    }
}
