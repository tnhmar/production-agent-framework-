package com.agentframework.core;
import com.agentframework.foundation.*;
import com.agentframework.observability.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
class StateMachineRunner {
    private final PlanValidator validator;
    private final EventSink     events;
    private static final int    MAX_CONSECUTIVE_FAILURES = 3;

    StateMachineRunner(PlanValidator validator, EventSink events) {
        this.validator=validator; this.events=events;
    }

    void run(Agent agent, ExecutionContext ctx) {
        while (ctx.currentState().isLive()) {
            step(agent, ctx);
            // Safety: prevent runaway loops beyond max cycles
            if (ctx.cycleCount() > ctx.task().maxCycles() + 5) {
                ctx.setTerminationReason(new TerminationReason.ResourceLimit("cycle overflow"));
                ctx.transitionTo(RunState.ABORTED);
                break;
            }
        }
    }

    private void step(Agent agent, ExecutionContext ctx) {
        switch (ctx.currentState()) {

            case INITIALIZED -> {
                ctx.goalStack().push(new Goal("root", null, GoalStatus.PENDING,
                    ctx.task().instruction(), java.util.List.of(), Budget.unlimited()));
                ctx.transitionTo(RunState.VALIDATING);
            }

            case VALIDATING -> {
                // Resource pre-check before each planning cycle
                TerminationReason limit = checkResourceLimits(ctx);
                if (limit != null) {
                    ctx.setTerminationReason(limit);
                    ctx.transitionTo(RunState.TERMINATED);
                    emit(ctx, AgentEvent.EventType.RESOURCE_LIMIT_HIT, Map.of("reason", limit.toString()));
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
                emit(ctx, AgentEvent.EventType.CYCLE_STARTED, Map.of("cycle", ctx.cycleCount()));
                Observations obs = agent.perception().perceive(ctx);
                ctx.transitionTo(RunState.MODEL_CALL);
                Decision decision = agent.reasoning().decide(ctx, obs);
                ctx.transitionTo(RunState.PLANNING); // reasoning impl may set MODEL_CALL

                ValidationResult validation = validator.validate(decision, ctx);
                switch (validation) {
                    case ValidationResult.Passed p -> {
                        ctx.transitionTo(RunState.TOOL_EXECUTION);
                        ActionResult result = agent.action().execute(decision, ctx);
                        ctx.transitionTo(RunState.MEMORY_UPDATE);
                        new Review(validator, events).step(result, decision, obs, ctx, agent);
                        ctx.recordCycle(new CycleRecord(ctx.cycleCount(), obs, decision, result, "ok"));
                        ctx.incrementCycle();
                        emit(ctx, AgentEvent.EventType.CYCLE_COMPLETED, Map.of("cycle", ctx.cycleCount()));
                        if (!ctx.currentState().isTerminal()) ctx.transitionTo(RunState.VALIDATING);
                    }
                    case ValidationResult.NeedsCorrection nc -> {
                        if (ctx.isRevisionBudgetExceeded(3)) {
                            ctx.setTerminationReason(new TerminationReason.Escalated("Correction budget exhausted"));
                            ctx.transitionTo(RunState.TERMINATED);
                        } else {
                            ctx.incrementRevisionCount();
                            ctx.flagPlanStale(nc.reason());
                            ctx.transitionTo(RunState.VALIDATING);
                        }
                    }
                    case ValidationResult.Failed f -> {
                        ctx.setTerminationReason(new TerminationReason.Escalated(f.reason()));
                        ctx.transitionTo(RunState.TERMINATED);
                    }
                }
            }

            case SUSPENDED_HITL, WAITING_FOR_JOB -> {
                // Sync runtime cannot handle async wait states
                ctx.setTerminationReason(new TerminationReason.FailureEscalation(
                    "State " + ctx.currentState() + " requires async runtime"));
                ctx.transitionTo(RunState.ABORTED);
            }

            case DEGRADED -> {
                ctx.setTerminationReason(new TerminationReason.FailureEscalation("Degraded state"));
                ctx.transitionTo(RunState.ABORTED);
            }

            default -> { /* TOOL_EXECUTION, MEMORY_UPDATE, etc. handled inside PLANNING case */ }
        }
    }

    private TerminationReason checkResourceLimits(ExecutionContext ctx) {
        Task t = ctx.task();
        if (ctx.cycleCount() >= t.maxCycles())
            return new TerminationReason.ResourceLimit("Max cycles: " + t.maxCycles());
        if (ctx.totalTokensUsed() >= t.maxTokens())
            return new TerminationReason.ResourceLimit("Max tokens: " + t.maxTokens());
        if (t.maxWallClockTime() != null) {
            java.time.Duration elapsed = java.time.Duration.between(ctx.startTime(), Instant.now());
            if (elapsed.compareTo(t.maxWallClockTime()) >= 0)
                return new TerminationReason.ResourceLimit("Wall clock exceeded");
        }
        if (t.budgetLimit() != null && ctx.totalCost().compareTo(t.budgetLimit()) >= 0)
            return new TerminationReason.ResourceLimit("Budget exceeded");
        return null;
    }

    private void emit(ExecutionContext ctx, AgentEvent.EventType type, Map<String,Object> attrs) {
        events.emit(new AgentEvent(ctx.runId(), ctx.tenantId(), type, Instant.now(), attrs));
    }
}
