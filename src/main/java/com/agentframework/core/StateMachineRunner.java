package com.agentframework.core;

import com.agentframework.foundation.*;
import com.agentframework.observability.*;
import com.agentframework.security.TaintClassifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the agent through its {@link RunState} machine.
 *
 * <p>Single responsibility: state-transition orchestration.
 * Liveness detection is delegated to {@link LivenessDetector}.
 * Taint classification is delegated to {@link TaintClassifier} (injected,
 * single shared instance per runner to preserve compiled pattern state).
 *
 * <h3>State machine</h3>
 * <pre>
 *   INITIALIZED → VALIDATING → PLANNING → MODEL_CALL → PLANNING
 *               ↪ (on limit)   → TOOL_EXECUTION → MEMORY_UPDATE
 *                               → TERMINATED / COMPLETED / ABORTED
 * </pre>
 *
 * <p><b>C4 fix — DEGRADED must transition to ABORTED, not TERMINATED</b>:
 * The spec (Vol. 1, Ch. 8) states: "There is no transition from DEGRADED
 * directly to TERMINATED — a degraded run must either recover or be explicitly
 * aborted."  A dedicated {@link #abort(ExecutionContext, TerminationReason,
 * AgentEvent.EventType, Map)} method now sets {@link RunState#ABORTED}.
 * The {@code DEGRADED} switch case calls {@code abort()} instead of
 * {@code terminate()}, eliminating the state/event contradiction where the
 * state said {@code TERMINATED} but the event said {@code RUN_ABORTED}.
 *
 * <p><b>Cycle record ordering fix:</b> {@code recordCycle()} and
 * {@code incrementCycle()} are called unconditionally at the end of every
 * PLANNING step, before the {@code isLive()} guard that returns early on
 * terminal state.  Previously the record was skipped whenever {@link Review}
 * transitioned the context to a terminal state during the same cycle.
 */
class StateMachineRunner {

    private final PlanValidator    validator;
    private final EventSink        events;
    private final LivenessDetector liveness;
    private final TaintClassifier  taintClassifier;

    /** Default constructor: creates one TaintClassifier per runner instance. */
    StateMachineRunner(PlanValidator validator, EventSink events) {
        this(validator, events, new DefaultLivenessDetector(), new TaintClassifier());
    }

    StateMachineRunner(PlanValidator validator, EventSink events,
                       LivenessDetector liveness, TaintClassifier taintClassifier) {
        this.validator       = validator;
        this.events          = events;
        this.liveness        = liveness;
        this.taintClassifier = taintClassifier;
    }

    void run(Agent agent, ExecutionContext ctx) {
        while (ctx.currentState().isLive()) {
            step(agent, ctx);
        }
    }

    private void step(Agent agent, ExecutionContext ctx) {
        switch (ctx.currentState()) {

            case INITIALIZED -> {
                Task t = ctx.task();
                int maxDepth = (t.maxChainDepth() > 0) ? t.maxChainDepth() : 10;
                if (ctx.currentChainDepth() > maxDepth) {
                    terminate(ctx, new TerminationReason.ResourceLimit(
                        "Delegation depth " + ctx.currentChainDepth() + " exceeds limit " + maxDepth),
                        AgentEvent.EventType.DELEGATION_DEPTH_EXCEEDED,
                        Map.of("depth", ctx.currentChainDepth(), "limit", maxDepth));
                    return;
                }
                Budget taskBudget = new Budget(
                    t.maxCycles(), t.maxTokens(),
                    t.maxWallClockTime()  != null ? t.maxWallClockTime()  : java.time.Duration.ofHours(24),
                    t.budgetLimit()       != null ? t.budgetLimit()       : java.math.BigDecimal.valueOf(Long.MAX_VALUE));
                ctx.goalStack().push(new Goal(
                    "root", null, GoalStatus.PENDING, t.instruction(),
                    java.util.List.of(), taskBudget));
                ctx.transitionTo(RunState.VALIDATING);
            }

            case VALIDATING -> {
                TerminationReason limit = checkResourceLimits(ctx);
                if (limit != null) {
                    terminate(ctx, limit, AgentEvent.EventType.RESOURCE_LIMIT_HIT,
                        Map.of("reason", limit.toString()));
                    return;
                }
                ctx.transitionTo(RunState.PLANNING);
            }

            case PLANNING -> {
                if (ctx.isPlanStale()) {
                    ctx.workingMemory().add(new WorkingMemoryEntry(
                        UUID.randomUUID().toString(),
                        "Plan correction required: " + ctx.stalenessHint(),
                        WorkingMemoryTier.ACTIVE, Origin.SYSTEM, 1.0,
                        Instant.now(), TaintLabel.CLEAN));
                    ctx.flagPlanStale(null);
                    emit(ctx, AgentEvent.EventType.PLAN_STALE, Map.of());
                }
                emit(ctx, AgentEvent.EventType.CYCLE_STARTED,
                    Map.of("cycle", ctx.cycleCount()));

                String preGoalHash = DefaultLivenessDetector.hashGoalState(
                    ctx.goalStack().all());

                Observations obs      = agent.perception().perceive(ctx);
                ctx.transitionTo(RunState.MODEL_CALL);
                Decision     decision = agent.reasoning().decide(ctx, obs);
                ctx.transitionTo(RunState.PLANNING);

                liveness.checkStuck(decision, ctx).ifPresent(reason -> {
                    terminate(ctx, reason,
                        AgentEvent.EventType.STUCK_STATE_DETECTED,
                        Map.of("cycles", ctx.stuckCycles(),
                               "lastDecision", decision.getClass().getSimpleName()));
                });
                if (!ctx.currentState().isLive()) return;

                ValidationResult validation = validator.validate(decision, ctx);
                switch (validation) {

                    case ValidationResult.Passed p -> {
                        ctx.transitionTo(RunState.TOOL_EXECUTION);
                        ActionResult result = agent.action().execute(decision, ctx);
                        ctx.transitionTo(RunState.MEMORY_UPDATE);
                        new Review(validator, events, taintClassifier)
                            .step(result, decision, obs, ctx, agent);

                        String postGoalHash = DefaultLivenessDetector.hashGoalState(
                            ctx.goalStack().all());
                        if (ctx.currentState().isLive()) {
                            liveness.checkStagnation(preGoalHash, postGoalHash, decision, ctx)
                                .ifPresent(reason -> terminate(ctx, reason,
                                    AgentEvent.EventType.GOAL_STAGNATION_DETECTED,
                                    Map.of("cycles", ctx.stagnantCycles(),
                                           "goalHash", postGoalHash)));
                        }

                        ctx.recordCycle(CycleRecord.of(
                            ctx.cycleCount(), obs, decision, result, "ok"));
                        ctx.incrementCycle();
                        emit(ctx, AgentEvent.EventType.CYCLE_COMPLETED,
                            Map.of("cycle", ctx.cycleCount()));

                        if (!ctx.currentState().isTerminal())
                            ctx.transitionTo(RunState.VALIDATING);
                    }

                    case ValidationResult.NeedsCorrection nc -> {
                        if (ctx.isRevisionBudgetExceeded(3)) {
                            terminate(ctx,
                                new TerminationReason.PlanIncoherent(
                                    "Revision budget exhausted: " + nc.reason()),
                                AgentEvent.EventType.PLAN_STALE,
                                Map.of("reason", nc.reason()));
                        } else {
                            ctx.incrementRevisionCount();
                            ctx.flagPlanStale(nc.reason());
                            ctx.transitionTo(RunState.VALIDATING);
                        }
                    }

                    case ValidationResult.Failed f -> terminate(ctx,
                        new TerminationReason.PlanIncoherent(f.reason()),
                        AgentEvent.EventType.PLAN_STALE,
                        Map.of("reason", f.reason()));
                }
            }

            case SUSPENDED_HITL, WAITING_FOR_JOB -> {
                terminate(ctx,
                    new TerminationReason.Escalated(
                        "State " + ctx.currentState() + " requires AsyncAgentRuntime."),
                    AgentEvent.EventType.HITL_REQUESTED,
                    Map.of("state", ctx.currentState().name()));
            }

            // C4 fix: DEGRADED must transition to ABORTED (not TERMINATED).
            // Spec Vol. 1 Ch. 8: "There is no transition from DEGRADED directly
            // to TERMINATED — a degraded run must either recover or be explicitly aborted."
            case DEGRADED -> abort(ctx,
                new TerminationReason.FailureEscalation("Agent entered DEGRADED state"),
                AgentEvent.EventType.RUN_ABORTED, Map.of());

            default -> {}
        }
    }

    private TerminationReason checkResourceLimits(ExecutionContext ctx) {
        Task t = ctx.task();
        if (ctx.cycleCount() >= t.maxCycles())
            return new TerminationReason.ResourceLimit("Max cycles: " + t.maxCycles());
        if (ctx.totalTokensUsed() >= t.maxTokens())
            return new TerminationReason.ResourceLimit("Max tokens: " + t.maxTokens());
        if (t.maxWallClockTime() != null) {
            java.time.Duration elapsed =
                java.time.Duration.between(ctx.startTime(), Instant.now());
            if (elapsed.compareTo(t.maxWallClockTime()) >= 0)
                return new TerminationReason.ResourceLimit("Wall-clock limit exceeded");
        }
        if (t.budgetLimit() != null
                && ctx.totalCost().compareTo(t.budgetLimit()) >= 0)
            return new TerminationReason.ResourceLimit("Budget limit exceeded");
        return null;
    }

    /**
     * C4 fix: transitions to {@link RunState#ABORTED} — used for DEGRADED
     * state escalation.  The spec forbids a direct DEGRADED → TERMINATED
     * transition; a degraded run must be explicitly aborted.
     */
    private void abort(ExecutionContext ctx, TerminationReason reason,
                       AgentEvent.EventType eventType, Map<String, Object> attrs) {
        ctx.setTerminationReason(reason);
        ctx.transitionTo(RunState.ABORTED);   // C4 fix: ABORTED, not TERMINATED
        emit(ctx, eventType, attrs);
    }

    /**
     * Transitions to {@link RunState#TERMINATED} — used for resource limits,
     * hostile taint blocks, stuck states, plan incoherence, and delegation
     * depth exceeded.
     */
    private void terminate(ExecutionContext ctx, TerminationReason reason,
                           AgentEvent.EventType eventType, Map<String, Object> attrs) {
        ctx.setTerminationReason(reason);
        ctx.transitionTo(RunState.TERMINATED);
        emit(ctx, eventType, attrs);
    }

    private void emit(ExecutionContext ctx, AgentEvent.EventType type,
                      Map<String, Object> attrs) {
        events.emit(new AgentEvent(
            ctx.runId(), ctx.tenantId(), type, Instant.now(), attrs));
    }
}
