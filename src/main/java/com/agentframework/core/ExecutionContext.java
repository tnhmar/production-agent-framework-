package com.agentframework.core;

import com.agentframework.foundation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Per-run execution context threaded through the entire agent lifecycle.
 *
 * <p>Extended with liveness counters ({@code stagnantCycles}, {@code stuckCycles})
 * required by the stagnation and stuck-state detectors in {@link StateMachineRunner}.
 */
public interface ExecutionContext {
    String         runId();
    String         tenantId();
    Instant        startTime();
    RequestContext requestContext();
    Task           task();

    RunState currentState();
    void     transitionTo(RunState state);

    int  cycleCount();
    void incrementCycle();
    int  consecutiveFailures();
    void incrementConsecutiveFailures();
    void resetConsecutiveFailures();
    int  currentChainDepth();
    void incrementChainDepth();
    void resetChainDepth();

    // ── Liveness counters (N1 + N2) ─────────────────────────────────────────
    /** Cycles where the goal-state hash was identical to the previous cycle. */
    int  stagnantCycles();
    void incrementStagnantCycles();
    void resetStagnantCycles();

    /** Cycles where the decision was neither a ToolCall, ParallelToolCalls,
     *  FinalAnswer, nor Escalate — i.e. the agent produced no forward progress. */
    int  stuckCycles();
    void incrementStuckCycles();
    void resetStuckCycles();
    // ────────────────────────────────────────────────────────────────────────

    GoalStack     goalStack();
    WorkingMemory workingMemory();
    BeliefState   beliefState();

    int     revisionCount();
    void    incrementRevisionCount();
    boolean isRevisionBudgetExceeded(int max);
    void    flagPlanStale(String hint);
    boolean isPlanStale();
    String  stalenessHint();

    Map<String, JobToken> activeJobs();

    void              recordCycle(CycleRecord r);
    List<CycleRecord> trace();

    int        totalTokensUsed();
    void       addTokens(int n);
    BigDecimal totalCost();
    void       addCost(BigDecimal c);

    Optional<TerminationReason> terminationReason();
    void setTerminationReason(TerminationReason r);

    Snapshot checkpoint();

    /**
     * Full, schema-versioned checkpoint snapshot.
     * Contains all state required for deterministic resume:
     * run ID, step index, state, goal stack, working memory entries,
     * beliefs, token/cost accumulators, and a SHA-256 integrity hash.
     */
    interface Snapshot {
        String                   runId();
        RunState                 state();
        int                      cycle();
        String                   schemaVersion();
        List<Goal>               goalStackSnapshot();
        List<WorkingMemoryEntry> workingMemorySnapshot();
        List<Belief>             beliefSnapshot();
        int                      totalTokens();
        BigDecimal               totalCost();
        String                   integrityHash();
    }
}
