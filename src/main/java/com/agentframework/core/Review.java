package com.agentframework.core;

import com.agentframework.foundation.*;
import com.agentframework.observability.*;
import com.agentframework.security.TaintClassifier;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Post-action review step executed inside each PLANNING cycle.
 *
 * <p>Key responsibility added here: every {@link ActionResult} written to working
 * memory is classified by {@link TaintClassifier} <em>before</em> the write.
 * Tool output is tagged {@link TaintLabel#EXTERNAL} by default; payloads matching
 * known injection patterns are tagged {@link TaintLabel#HOSTILE}, which will block
 * further tool dispatch via {@code TaintActionValidator} on the next cycle.
 *
 * <p>Internal results (validation failures, clarifications, escalations) are
 * system-generated and remain {@link TaintLabel#CLEAN}.
 */
class Review {
    private final PlanValidator   validator;
    private final EventSink       events;
    private final TaintClassifier taintClassifier;
    private static final int      MAX_FAILURES = 3;

    /** Production constructor — uses shared TaintClassifier instance. */
    Review(PlanValidator validator, EventSink events) {
        this(validator, events, new TaintClassifier());
    }

    /** Testable constructor — allows injecting a custom TaintClassifier. */
    Review(PlanValidator validator, EventSink events, TaintClassifier taintClassifier) {
        this.validator       = validator;
        this.events          = events;
        this.taintClassifier = taintClassifier;
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

        // 2. Evict if context window is getting full
        new ContextWindowManager().manage(ctx.workingMemory(), ctx.task().maxTokens());

        // 3. Update goal stack based on decision outcome
        updateGoals(result, decision, ctx);

        // 4. Update belief state
        updateBeliefs(result, ctx);

        // 5. Memory write-back via agent's long-term memory
        if (result instanceof ActionResult.Success s && s.result().indicatesWorldChange()) {
            try {
                agent.memory().write(
                    com.agentframework.memory.MemoryContent.text(summary),
                    com.agentframework.memory.MemoryType.EPISODIC,
                    com.agentframework.memory.MemoryMetadata.of(0.7, "tool_result"),
                    ctx.requestContext());
            } catch (Exception ignored) {}
        }

        // 6. Re-validate plan after world-changing action
        if (result.indicatesWorldChange()) {
            ValidationResult reval = validator.validateAfterAction(result, ctx);
            if (reval instanceof ValidationResult.NeedsCorrection nc) {
                ctx.incrementRevisionCount();
                if (ctx.isRevisionBudgetExceeded(3)) {
                    ctx.setTerminationReason(new TerminationReason.Escalated("Revision budget exhausted"));
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

    /**
     * Derives the taint label for the result being written to working memory.
     *
     * <ul>
     *   <li>Success / Failure — external data; classify the payload content.</li>
     *   <li>PartialSuccess — hostile if ANY individual result is hostile.</li>
     *   <li>ValidationFailure / Escalated / Clarification — system-generated; CLEAN.</li>
     * </ul>
     */
    private TaintLabel classifyResultTaint(ActionResult result) {
        return switch (result) {
            case ActionResult.Success s -> taintClassifier.classifyObject(s.result().data());
            case ActionResult.PartialSuccess ps -> {
                boolean anyHostile = ps.results().stream()
                    .anyMatch(r -> taintClassifier.classifyObject(r.data()) == TaintLabel.HOSTILE);
                yield anyHostile ? TaintLabel.HOSTILE : TaintLabel.EXTERNAL;
            }
            case ActionResult.Failure f ->
                taintClassifier.classify(f.message()); // error messages may carry injection text
            case ActionResult.ValidationFailure __,
                 ActionResult.Escalated __,
                 ActionResult.Clarification __ -> TaintLabel.CLEAN;
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
            case Escalate e     -> goals.current().ifPresent(g -> goals.updateStatus(g.id(), GoalStatus.FAILED));
            default -> {}
        }
    }

    private void updateBeliefs(ActionResult result, ExecutionContext ctx) {
        if (!(result instanceof ActionResult.Success s)) return;
        String value = s.result().data() != null ? s.result().data().toString() : "null";
        Belief incoming = new Belief(UUID.randomUUID().toString(),
            "last_tool_result", "equals", value,
            0.8, "tool_result", Instant.now(), false);
        Belief won = ctx.beliefState().assertBelief(incoming);
        if (won.conflicted())
            events.emit(new AgentEvent(ctx.runId(), ctx.tenantId(),
                AgentEvent.EventType.BELIEF_CONFLICT, Instant.now(),
                Map.of("key", won.subject() + "|" + won.predicate())));
    }

    private TerminationReason checkTermination(ActionResult result, Decision decision, ExecutionContext ctx) {
        if (ctx.goalStack().isRootAchieved()) return new TerminationReason.GoalCompleted();
        if (decision instanceof Escalate e)   return new TerminationReason.Escalated(e.reason());
        if (decision instanceof FinalAnswer)   return new TerminationReason.GoalCompleted();

        boolean isFailure = result instanceof ActionResult.Failure
                         || result instanceof ActionResult.ValidationFailure;
        if (isFailure) {
            ctx.incrementConsecutiveFailures();
            if (ctx.consecutiveFailures() >= MAX_FAILURES)
                return new TerminationReason.FailureEscalation("Too many consecutive failures");
        } else {
            ctx.resetConsecutiveFailures();
        }
        return null;
    }

    private String summarize(ActionResult r) {
        return switch (r) {
            case ActionResult.Success s ->
                "SUCCESS: " + (s.result().data() != null
                    ? s.result().data().toString().substring(0, Math.min(200, s.result().data().toString().length()))
                    : "null");
            case ActionResult.Failure f            -> "FAILURE[" + f.errorCode() + "]: " + f.message();
            case ActionResult.ValidationFailure vf -> "VALIDATION_FAILURE: " + vf.verdict();
            case ActionResult.PartialSuccess ps    -> "PARTIAL: " + ps.results().size() + " ok, " + ps.errors().size() + " err";
            case ActionResult.Escalated e          -> "ESCALATED: " + e.reason();
            case ActionResult.Clarification c      -> "CLARIFY: " + c.question();
        };
    }
}
