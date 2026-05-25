package com.agentframework.core;

import com.agentframework.foundation.*;
import com.agentframework.observability.*;
import com.agentframework.security.TaintClassifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Post-action review step executed inside each PLANNING cycle.
 *
 * <p>All collaborators are constructor-injected. In particular:
 * <ul>
 *   <li>{@link TaintClassifier} is injected, not constructed inline, so callers
 *       can share a single instance with pre-compiled pattern lists.</li>
 *   <li>{@link ContextWindowManager} is injected to allow limit overrides in tests.</li>
 * </ul>
 *
 * <p>Package-private — instantiated only by {@link StateMachineRunner}.
 */
class Review {

    private static final Logger LOG = Logger.getLogger(Review.class.getName());

    private final PlanValidator        validator;
    private final EventSink            events;
    private final TaintClassifier      taintClassifier;
    private final ContextWindowManager ctxManager;

    private static final int MAX_FAILURES = 3;

    /** Minimal constructor used by the default {@link StateMachineRunner}. */
    Review(PlanValidator validator, EventSink events,
           TaintClassifier taintClassifier) {
        this(validator, events, taintClassifier, new ContextWindowManager());
    }

    /** Full constructor — all dependencies injected. */
    Review(PlanValidator validator, EventSink events,
           TaintClassifier taintClassifier, ContextWindowManager ctxManager) {
        this.validator        = validator;
        this.events           = events;
        this.taintClassifier  = taintClassifier;
        this.ctxManager       = ctxManager;
    }

    void step(ActionResult result, Decision decision, Observations obs,
              ExecutionContext ctx, Agent agent) {

        // 1. Classify taint BEFORE writing to working memory
        TaintLabel taint   = classifyResultTaint(result);
        String     summary = summarize(result);

        ctx.workingMemory().add(new WorkingMemoryEntry(
            UUID.randomUUID().toString(), summary,
            WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.9,
            Instant.now(), taint));

        if (taint == TaintLabel.HOSTILE) {
            events.emit(new AgentEvent(ctx.runId(), ctx.tenantId(),
                AgentEvent.EventType.HOSTILE_TAINT_DETECTED, Instant.now(),
                Map.of("summary", summary.substring(0, Math.min(200, summary.length())))));
        }

        // 2. Context-window management (injected — no inline construction)
        ctxManager.manage(ctx.workingMemory(), ctx.task().maxTokens());

        // 3. Update goal stack
        updateGoals(result, decision, ctx);

        // 4. Update belief state
        updateBeliefs(result, ctx);

        // 5. Long-term memory write-back for world-changing actions
        if (result instanceof ActionResult.Success s && s.result().indicatesWorldChange()) {
            try {
                agent.memory().write(
                    com.agentframework.memory.MemoryContent.text(summary),
                    com.agentframework.memory.MemoryType.EPISODIC,
                    com.agentframework.memory.MemoryMetadata.of(0.7, "tool_result"),
                    ctx.requestContext());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Memory write-back failed for run " + ctx.runId(), e);
            }
        }

        // 6. Re-validate plan after world-changing action
        if (result.indicatesWorldChange()) {
            ValidationResult reval = validator.validateAfterAction(result, ctx);
            if (reval instanceof ValidationResult.NeedsCorrection nc) {
                ctx.incrementRevisionCount();
                if (ctx.isRevisionBudgetExceeded(3)) {
                    ctx.setTerminationReason(
                        new TerminationReason.PlanIncoherent(
                            "Revision budget exhausted: " + nc.reason()));
                    ctx.transitionTo(RunState.TERMINATED);
                } else {
                    ctx.flagPlanStale(nc.reason());
                }
                return;
            }
        }

        // 7. Check termination conditions
        TerminationReason reason = checkTermination(result, decision, ctx);
        if (reason != null) {
            ctx.setTerminationReason(reason);
            ctx.transitionTo(RunState.COMPLETED);
        }
    }

    private TaintLabel classifyResultTaint(ActionResult result) {
        return switch (result) {
            case ActionResult.Success s         -> taintClassifier.classifyObject(s.result().data());
            case ActionResult.PartialSuccess ps -> {
                boolean anyHostile = ps.results().stream()
                    .anyMatch(r -> taintClassifier.classifyObject(r.data()) == TaintLabel.HOSTILE);
                yield anyHostile ? TaintLabel.HOSTILE : TaintLabel.EXTERNAL;
            }
            case ActionResult.Failure f         -> taintClassifier.classify(f.message());
            case ActionResult.ValidationFailure v1 -> TaintLabel.CLEAN;
            case ActionResult.Escalated         v2 -> TaintLabel.CLEAN;
            case ActionResult.Clarification     v3 -> TaintLabel.CLEAN;
        };
    }

    private void updateGoals(ActionResult result, Decision decision, ExecutionContext ctx) {
        GoalStack goals = ctx.goalStack();
        switch (decision) {
            case ToolCall tc -> {
                if (result instanceof ActionResult.Success) {
                    goals.current().ifPresent(g -> {
                        if (!g.id().equals("root")) goals.updateStatus(g.id(), GoalStatus.COMPLETED);
                    });
                } else if (result instanceof ActionResult.Failure) {
                    goals.current().ifPresent(g -> goals.updateStatus(g.id(), GoalStatus.FAILED));
                }
            }
            case FinalAnswer fa -> goals.updateStatus("root", GoalStatus.COMPLETED);
            case Escalate e     -> goals.current().ifPresent(
                    g -> goals.updateStatus(g.id(), GoalStatus.FAILED));
            default -> {}
        }
    }

    private void updateBeliefs(ActionResult result, ExecutionContext ctx) {
        if (!(result instanceof ActionResult.Success s)) return;
        String value = s.result().data() != null ? s.result().data().toString() : "null";
        Belief incoming = new Belief(UUID.randomUUID().toString(),
            "last_tool_result", "equals", value, 0.8,
            "tool_result", Instant.now(), false);
        Belief won = ctx.beliefState().assertBelief(incoming);
        if (won.conflicted())
            events.emit(new AgentEvent(ctx.runId(), ctx.tenantId(),
                AgentEvent.EventType.BELIEF_CONFLICT, Instant.now(),
                Map.of("key", won.subject() + "|" + won.predicate())));
    }

    /**
     * RV-1 fix: every non-failure ActionResult variant now explicitly resets
     * the consecutive-failure counter so a preceding failure streak cannot
     * persist silently after an Escalated or Clarification result.
     *
     * <p>Variants that count as failure: {@link ActionResult.Failure} and
     * {@link ActionResult.ValidationFailure}.<br>
     * All other variants ({@link ActionResult.Success},
     * {@link ActionResult.PartialSuccess}, {@link ActionResult.Escalated},
     * {@link ActionResult.Clarification}) reset the counter.
     */
    private TerminationReason checkTermination(
            ActionResult result, Decision decision, ExecutionContext ctx) {
        if (ctx.goalStack().isRootAchieved()) return new TerminationReason.GoalCompleted();
        if (decision instanceof Escalate e)   return new TerminationReason.Escalated(e.reason());
        if (decision instanceof FinalAnswer)   return new TerminationReason.GoalCompleted();

        boolean isFailure = result instanceof ActionResult.Failure
                         || result instanceof ActionResult.ValidationFailure;
        if (isFailure) {
            ctx.incrementConsecutiveFailures();
            if (ctx.consecutiveFailures() >= MAX_FAILURES)
                return new TerminationReason.FailureEscalation(
                    "Consecutive failure threshold reached (" + MAX_FAILURES + ")");
        } else {
            ctx.resetConsecutiveFailures();
        }
        return null;
    }

    private String summarize(ActionResult r) {
        return switch (r) {
            case ActionResult.Success s -> {
                String data = s.result().data() != null
                    ? s.result().data().toString() : "null";
                yield "SUCCESS: " + data.substring(0, Math.min(200, data.length()));
            }
            case ActionResult.Failure f            -> "FAILURE[" + f.errorCode() + "]: " + f.message();
            case ActionResult.ValidationFailure vf -> "VALIDATION_FAILURE: " + vf.verdict();
            case ActionResult.PartialSuccess ps    -> "PARTIAL: " + ps.results().size() + " ok, " + ps.errors().size() + " err";
            case ActionResult.Escalated e          -> "ESCALATED: " + e.reason();
            case ActionResult.Clarification c      -> "CLARIFY: " + c.question();
        };
    }
}
