package com.agentframework.core;

import com.agentframework.foundation.*;
import com.agentframework.observability.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Drives the per-cycle execution loop of an {@link Agent}.
 *
 * <p>The runner owns the outermost control flow: it transitions the context
 * through the Volume 1 state-machine, enforces all budget limits, calls
 * {@link Review} after each cycle, and delegates liveness detection to
 * {@link LivenessDetector}.
 *
 * <p><b>C-3 fix:</b> the runner now calls {@link ExecutionContext#checkpoint()}
 * automatically at two risk boundaries defined in Volume 1:
 * <ol>
 *   <li><em>Before every irreversible action</em> — a checkpoint is taken
 *       immediately before the action layer executes each cycle (after
 *       perception and reasoning have completed but before side-effects fire).</li>
 *   <li><em>After each major subgoal completion</em> — a checkpoint is taken
 *       after {@link Review#step} returns cleanly when the top-of-stack goal
 *       transitions to {@code COMPLETED}.</li>
 * </ol>
 * Snapshots are passed to the optional {@link CheckpointStore} injected at
 * construction time.  When no store is provided the snapshots are created
 * (maintaining hash integrity) but not persisted — this is a valid
 * configuration for non-resumable runs.
 */
public class StateMachineRunner {

    static final int MAX_CONSECUTIVE_FAILURES = 3;
    static final int MAX_REVISION_LOOPS       = 5;

    private final Agent             agent;
    private final ExecutionContext  ctx;
    private final LivenessDetector  liveness;
    private final CheckpointStore   checkpointStore;   // nullable
    private final RunObserver       observer;

    public StateMachineRunner(Agent agent, ExecutionContext ctx,
                              LivenessDetector liveness, RunObserver observer) {
        this(agent, ctx, liveness, null, observer);
    }

    public StateMachineRunner(Agent agent, ExecutionContext ctx,
                              LivenessDetector liveness,
                              CheckpointStore checkpointStore,
                              RunObserver observer) {
        this.agent           = agent;
        this.ctx             = ctx;
        this.liveness        = liveness;
        this.checkpointStore = checkpointStore;
        this.observer        = observer;
    }

    // ── Main loop ────────────────────────────────────────────────────

    public void run(Budget budget) {
        ctx.transitionTo(RunState.PLANNING);
        observer.onRunStart(ctx);

        while (ctx.currentState() == RunState.PLANNING
               || ctx.currentState() == RunState.EXECUTING) {

            if (ctx.currentState() == RunState.PLANNING) {
                ctx.transitionTo(RunState.EXECUTING);
            }

            ctx.incrementCycle();

            // ── Budget checks ─────────────────────────────────────
            if (budget.maxCycles() > 0 && ctx.cycleCount() > budget.maxCycles()) {
                terminate(new TerminationReason.CycleLimitExceeded(ctx.cycleCount(),
                          budget.maxCycles()), budget);
                return;
            }
            if (budget.maxTokens() > 0 && ctx.totalTokensUsed() >= budget.maxTokens()) {
                terminate(new TerminationReason.TokenBudgetExceeded(ctx.totalTokensUsed(),
                          budget.maxTokens()), budget);
                return;
            }
            if (budget.maxTime() != null) {
                long elapsed = Instant.now().toEpochMilli() - ctx.startTime().toEpochMilli();
                if (elapsed >= budget.maxTime().toMillis()) {
                    terminate(new TerminationReason.TimeLimitExceeded(elapsed,
                              budget.maxTime().toMillis()), budget);
                    return;
                }
            }
            if (budget.maxCost() != null && ctx.totalCost().compareTo(budget.maxCost()) >= 0) {
                terminate(new TerminationReason.CostLimitExceeded(ctx.totalCost(),
                          budget.maxCost()), budget);
                return;
            }

            // ── Pre-action checkpoint (C-3: risk boundary 1) ─────
            takeCheckpoint("pre-action:cycle:" + ctx.cycleCount());

            // ── Goal-state hash before cycle (for N1 stagnation) ─
            String preHash = DefaultLivenessDetector.hashGoalState(ctx.goalStack().all());

            Instant cycleStart = Instant.now();
            Decision decision;

            try {
                // ── Perception ───────────────────────────────────
                PerceptionResult perception = agent.perception().perceive(ctx);

                // ── Reasoning ────────────────────────────────────
                decision = agent.reasoning().decide(ctx, perception);
                ctx.addTokens(decision.tokensUsed());

                // ── Action ───────────────────────────────────────
                ActionResult actionResult = agent.action().execute(ctx, decision);

                // ── Memory write-back ─────────────────────────────
                agent.memory().update(ctx, perception, decision, actionResult);

                // ── Post-cycle review ─────────────────────────────
                Goal topBefore = ctx.goalStack().current().orElse(null);
                Review.step(ctx, decision, actionResult, agent.planValidator());

                // ── Post-subgoal checkpoint (C-3: risk boundary 2) ─
                Goal topAfter = ctx.goalStack().current().orElse(null);
                boolean subgoalCompleted =
                    topBefore != null
                    && (topAfter == null || !topAfter.id().equals(topBefore.id()))
                    && ctx.goalStack().all().stream()
                          .anyMatch(g -> g.id().equals(topBefore.id())
                                        && g.status() == GoalStatus.COMPLETED);
                if (subgoalCompleted) {
                    takeCheckpoint("post-subgoal:" + topBefore.id());
                }

                // ── Liveness ─────────────────────────────────────
                String postHash = DefaultLivenessDetector.hashGoalState(ctx.goalStack().all());

                Optional<TerminationReason> stagnation =
                    liveness.checkStagnation(preHash, postHash, decision, ctx);
                if (stagnation.isPresent()) {
                    terminate(stagnation.get(), budget);
                    return;
                }

                Optional<TerminationReason> stuck =
                    liveness.checkStuck(decision, ctx);
                if (stuck.isPresent()) {
                    terminate(stuck.get(), budget);
                    return;
                }

                // ── Cycle trace ───────────────────────────────────
                observer.onCycleComplete(ctx, new CycleRecord(
                    ctx.cycleCount(), Instant.now().toEpochMilli() - cycleStart.toEpochMilli(),
                    decision, actionResult));

            } catch (SecurityException se) {
                ctx.setTerminationReason(new TerminationReason.SecurityViolation(se.getMessage()));
                ctx.transitionTo(RunState.ABORTED);
                observer.onRunEnd(ctx);
                return;
            } catch (Exception e) {
                ctx.incrementConsecutiveFailures();
                observer.onCycleError(ctx, e);
                if (ctx.consecutiveFailures() >= MAX_CONSECUTIVE_FAILURES) {
                    terminate(new TerminationReason.FailureEscalation(
                        ctx.consecutiveFailures(), e.getMessage()), budget);
                    return;
                }
            }

            // ── Terminal state check after review ─────────────────
            if (ctx.currentState() == RunState.COMPLETED
                || ctx.currentState() == RunState.TERMINATED
                || ctx.currentState() == RunState.ABORTED) {
                observer.onRunEnd(ctx);
                return;
            }
        }

        observer.onRunEnd(ctx);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void terminate(TerminationReason reason, Budget budget) {
        ctx.setTerminationReason(reason);
        ctx.transitionTo(RunState.TERMINATED);
        observer.onRunEnd(ctx);
    }

    /**
     * C-3: Takes and optionally persists a checkpoint.
     * The snapshot is always computed (maintaining hash integrity).
     * If a {@link CheckpointStore} was injected it receives the snapshot;
     * otherwise the snapshot is created and discarded — the run is not resumable
     * but no silent correctness gap exists.
     */
    private void takeCheckpoint(String label) {
        try {
            Snapshot snap = ctx.checkpoint();
            if (checkpointStore != null) {
                checkpointStore.save(label, snap);
            }
        } catch (Exception e) {
            // Checkpoint failure must never abort the run — log and continue.
            // The run loses replay capability for this boundary but remains safe.
            observer.onCheckpointFailure(ctx, label, e);
        }
    }
}
