package com.agentframework.core;

import com.agentframework.foundation.*;
import com.agentframework.observability.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Drives the agent through its RunState machine.
 *
 * <p>Three liveness detectors added for full Volume 1 conformity:
 * <ul>
 *   <li><b>N3</b> – delegation depth enforcement: aborts if {@code ctx.currentChainDepth()}
 *       exceeds the task's {@code maxChainDepth} (default 10) at INITIALIZED.</li>
 *   <li><b>N2</b> – stuck-state detector: terminates after {@value #MAX_STUCK_CYCLES}
 *       consecutive cycles where the model produces neither a tool call nor a terminal
 *       decision.</li>
 *   <li><b>N1</b> – goal-stagnation detector: terminates after {@value #MAX_STAGNANT_CYCLES}
 *       consecutive cycles where the SHA-256 goal-state hash is identical post-cycle,
 *       indicating the agent is looping without advancing toward its goal.</li>
 * </ul>
 */
class StateMachineRunner {
    private final PlanValidator validator;
    private final EventSink     events;

    private static final int MAX_STAGNANT_CYCLES     = 3;
    private static final int MAX_STUCK_CYCLES        = 2;
    private static final int DEFAULT_MAX_CHAIN_DEPTH = 10;

    StateMachineRunner(PlanValidator validator, EventSink events) {
        this.validator = validator;
        this.events    = events;
    }

    void run(Agent agent, ExecutionContext ctx) {
        while (ctx.currentState().isLive()) {
            step(agent, ctx);
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
                Task t = ctx.task();

                // N3: enforce delegation depth before any work begins
                int maxDepth = (t.maxChainDepth() > 0) ? t.maxChainDepth() : DEFAULT_MAX_CHAIN_DEPTH;
                if (ctx.currentChainDepth() > maxDepth) {
                    ctx.setTerminationReason(new TerminationReason.ResourceLimit(
                        "Delegation depth " + ctx.currentChainDepth() + " exceeds limit " + maxDepth));
                    emit(ctx, AgentEvent.EventType.DELEGATION_DEPTH_EXCEEDED,
                        Map.of("depth", ctx.currentChainDepth(), "limit", maxDepth));
                    ctx.transitionTo(RunState.ABORTED);
                    return;
                }

                Budget taskBudget = new Budget(
                    t.maxCycles(), t.maxTokens(),
                    t.maxWallClockTime() != null ? t.maxWallClockTime() : java.time.Duration.ofHours(24),
                    t.budgetLimit()      != null ? t.budgetLimit()      : java.math.BigDecimal.valueOf(Long.MAX_VALUE));
                ctx.goalStack().push(new Goal("root", null, GoalStatus.PENDING,
                    t.instruction(), List.of(), taskBudget));
                ctx.transitionTo(RunState.VALIDATING);
            }

            case VALIDATING -> {
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

                // N1: snapshot goal-state hash BEFORE the model call
                String preGoalHash = hashGoalState(ctx.goalStack().all());

                Observations obs      = agent.perception().perceive(ctx);
                ctx.transitionTo(RunState.MODEL_CALL);
                Decision     decision = agent.reasoning().decide(ctx, obs);
                ctx.transitionTo(RunState.PLANNING);

                // N2: stuck-state detector
                if (isNonProgressDecision(decision)) {
                    ctx.incrementStuckCycles();
                    if (ctx.stuckCycles() >= MAX_STUCK_CYCLES) {
                        ctx.setTerminationReason(new TerminationReason.Escalated(
                            "Agent stuck: " + ctx.stuckCycles() +
                            " consecutive cycles with no tool call and no terminal response"));
                        emit(ctx, AgentEvent.EventType.STUCK_STATE_DETECTED,
                            Map.of("cycles", ctx.stuckCycles(),
                                   "lastDecision", decision.getClass().getSimpleName()));
                        ctx.transitionTo(RunState.TERMINATED);
                        return;
                    }
                } else {
                    ctx.resetStuckCycles();
                }

                ValidationResult validation = validator.validate(decision, ctx);
                switch (validation) {
                    case ValidationResult.Passed p -> {
                        ctx.transitionTo(RunState.TOOL_EXECUTION);
                        ActionResult result = agent.action().execute(decision, ctx);
                        ctx.transitionTo(RunState.MEMORY_UPDATE);
                        new Review(validator, events).step(result, decision, obs, ctx, agent);

                        // N1: stagnation detector — compare goal hash after cycle completes
                        String postGoalHash = hashGoalState(ctx.goalStack().all());
                        if (preGoalHash.equals(postGoalHash) && isToolOrAnswer(decision)) {
                            ctx.incrementStagnantCycles();
                            if (ctx.stagnantCycles() >= MAX_STAGNANT_CYCLES) {
                                ctx.setTerminationReason(new TerminationReason.Escalated(
                                    "Goal state unchanged for " + ctx.stagnantCycles() +
                                    " consecutive cycles — possible infinite loop"));
                                emit(ctx, AgentEvent.EventType.GOAL_STAGNATION_DETECTED,
                                    Map.of("cycles", ctx.stagnantCycles(),
                                           "goalHash", postGoalHash));
                                ctx.transitionTo(RunState.TERMINATED);
                                return;
                            }
                        } else {
                            ctx.resetStagnantCycles();
                        }

                        ctx.recordCycle(CycleRecord.of(ctx.cycleCount(), obs, decision, result, "ok"));
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
                ctx.setTerminationReason(new TerminationReason.FailureEscalation(
                    "State " + ctx.currentState() + " requires an async runtime. " +
                    "Implement an async AgentRuntime that handles SUSPENDED_HITL via ExecutionStore."));
                ctx.transitionTo(RunState.ABORTED);
            }

            case DEGRADED -> {
                ctx.setTerminationReason(new TerminationReason.FailureEscalation("Degraded state"));
                ctx.transitionTo(RunState.ABORTED);
            }

            default -> {}
        }
    }

    /** N2: progress requires a tool call, parallel call, final answer, or escalation. */
    private static boolean isNonProgressDecision(Decision d) {
        return !(d instanceof ToolCall)
            && !(d instanceof ParallelToolCalls)
            && !(d instanceof FinalAnswer)
            && !(d instanceof Escalate);
    }

    /** N1: only count stagnation when the agent actually attempted tool work or gave an answer. */
    private static boolean isToolOrAnswer(Decision d) {
        return d instanceof ToolCall || d instanceof ParallelToolCalls || d instanceof FinalAnswer;
    }

    /**
     * N1: SHA-256 over "id=STATUS" pairs for every goal in the stack.
     * Returns first 8 hex chars — sufficient for per-run stagnation detection.
     */
    private static String hashGoalState(List<Goal> goals) {
        String canonical = goals.stream()
            .map(g -> g.id() + "=" + g.status().name())
            .collect(Collectors.joining(","));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return canonical; // fallback: plain string comparison
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
