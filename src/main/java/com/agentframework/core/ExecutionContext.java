package com.agentframework.core;

import com.agentframework.foundation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

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

    void             recordCycle(CycleRecord r);
    List<CycleRecord> trace();

    int        totalTokensUsed();
    void       addTokens(int n);
    BigDecimal totalCost();
    void       addCost(BigDecimal c);

    Optional<TerminationReason> terminationReason();
    void setTerminationReason(TerminationReason r);

    Snapshot checkpoint();

    /**
     * Full, schema-versioned checkpoint snapshot (C1 fix).
     * Contains all state required for deterministic resume:
     * run ID, step index, state, goal stack, working memory entries,
     * beliefs, token/cost accumulators, and a SHA-256 integrity hash.
     */
    interface Snapshot {
        String                   runId();
        RunState                 state();
        int                      cycle();
        // Extended fields for full checkpoint conformance
        String                   schemaVersion();
        List<Goal>               goalStackSnapshot();
        List<WorkingMemoryEntry> workingMemorySnapshot();
        List<Belief>             beliefSnapshot();
        int                      totalTokens();
        BigDecimal               totalCost();
        String                   integrityHash();   // SHA-256 of canonical JSON
    }
}
