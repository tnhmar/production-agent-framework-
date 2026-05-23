package com.agentframework.core;

import com.agentframework.foundation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Per-run execution context threaded through the entire agent lifecycle.
 *
 * <p>The {@link Snapshot} inner interface includes liveness counters so that
 * the SHA-256 integrity hash covers all fields that influence agent behaviour.
 * Any tampering with liveness counters (e.g. resetting consecutiveFailures to 0
 * to evade early-termination guards) is detected during replay verification.
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

    int  stagnantCycles();
    void incrementStagnantCycles();
    void resetStagnantCycles();
    int  stuckCycles();
    void incrementStuckCycles();
    void resetStuckCycles();

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
     *
     * <p>Every field that influences agent behaviour during a run is included
     * so the SHA-256 integrity hash can detect any mutation — including
     * modifications to working-memory content, belief values, and liveness
     * counters.
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
        // Liveness counters — must be part of the integrity hash
        int                      consecutiveFailures();
        int                      stagnantCycles();
        int                      stuckCycles();
        int                      revisionCount();
        String                   integrityHash();
    }
}
