package com.agentframework.core;

import com.agentframework.foundation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class DefaultExecutionContext implements ExecutionContext {
    /** Schema version for checkpoint format — increment on breaking changes. */
    public static final String SNAPSHOT_SCHEMA_VERSION = "1.0";

    private final String         runId       = UUID.randomUUID().toString();
    private final RequestContext reqCtx;
    private final Instant        startTime   = Instant.now();
    private final Task           task;
    private RunState   state          = RunState.INITIALIZED;
    private int        cycle          = 0;
    private int        consFailures   = 0;
    private int        chainDepth     = 0;
    private int        revisions      = 0;
    private boolean    planStale      = false;
    private String     stalenessHint;
    private final DefaultGoalStack     goalStack     = new DefaultGoalStack();
    private final DefaultWorkingMemory workingMemory = new DefaultWorkingMemory();
    private final DefaultBeliefState   beliefState   = new DefaultBeliefState();
    private final Map<String, JobToken> activeJobs   = new ConcurrentHashMap<>();
    private final List<CycleRecord>     trace        = Collections.synchronizedList(new ArrayList<>());
    private int        totalTokens  = 0;
    private BigDecimal totalCost    = BigDecimal.ZERO;
    private TerminationReason terminationReason;

    /**
     * Creates a context with explicit tenant and user.
     * IC5 fix: null tenantId is rejected rather than silently defaulting to "default" (permissive).
     */
    public DefaultExecutionContext(Task task, String tenantId, String userId) {
        this.task   = Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null — pass an explicit tenant ID. " +
            "Use TenantPolicy.permissive(tenantId) to register an unrestricted policy.");
        this.reqCtx = RequestContext.of(tenantId, userId != null ? userId : "system");
    }

    public String         runId()              { return runId; }
    public String         tenantId()           { return reqCtx.tenantId(); }
    public Instant        startTime()          { return startTime; }
    public RequestContext requestContext()      { return reqCtx; }
    public Task           task()               { return task; }
    public RunState       currentState()       { return state; }
    public void           transitionTo(RunState s) { this.state = Objects.requireNonNull(s); }
    public int            cycleCount()         { return cycle; }
    public void           incrementCycle()     { cycle++; }
    public int            consecutiveFailures(){ return consFailures; }
    public void  incrementConsecutiveFailures(){ consFailures++; }
    public void  resetConsecutiveFailures()    { consFailures = 0; }
    public int   currentChainDepth()           { return chainDepth; }
    public void  incrementChainDepth()         { chainDepth++; }
    public void  resetChainDepth()             { chainDepth = 0; }
    public GoalStack     goalStack()           { return goalStack; }
    public WorkingMemory workingMemory()       { return workingMemory; }
    public BeliefState   beliefState()         { return beliefState; }
    public int     revisionCount()             { return revisions; }
    public void    incrementRevisionCount()    { revisions++; }
    public boolean isRevisionBudgetExceeded(int max) { return revisions > max; }
    public void    flagPlanStale(String hint)  { planStale = hint != null; stalenessHint = hint; }
    public boolean isPlanStale()               { return planStale; }
    public String  stalenessHint()             { return stalenessHint; }
    public Map<String, JobToken> activeJobs()  { return activeJobs; }
    public void    recordCycle(CycleRecord r)  { trace.add(r); }
    public List<CycleRecord> trace()           { return new ArrayList<>(trace); }
    public int        totalTokensUsed()        { return totalTokens; }
    public void       addTokens(int n)         { totalTokens += n; }
    public BigDecimal totalCost()              { return totalCost; }
    public void       addCost(BigDecimal c)    { totalCost = totalCost.add(c); }
    public Optional<TerminationReason> terminationReason() { return Optional.ofNullable(terminationReason); }
    public void setTerminationReason(TerminationReason r)  { terminationReason = r; }

    /**
     * C1 fix: produces a full, integrity-hashed checkpoint snapshot.
     */
    public Snapshot checkpoint() {
        List<Goal>               goals   = goalStack.all();
        List<WorkingMemoryEntry> wm      = workingMemory.getAll();
        List<Belief>             beliefs = beliefState.all(0.0);
        String hash = computeHash(runId, state, cycle, goals, wm, beliefs, totalTokens, totalCost);
        return new FullSnapshot(
            runId, state, cycle,
            SNAPSHOT_SCHEMA_VERSION,
            Collections.unmodifiableList(goals),
            Collections.unmodifiableList(wm),
            Collections.unmodifiableList(beliefs),
            totalTokens, totalCost, hash);
    }

    // -------------------------------------------------------------------------
    // Full snapshot record (C1 fix)
    // -------------------------------------------------------------------------
    record FullSnapshot(
        String                   runId,
        RunState                 state,
        int                      cycle,
        String                   schemaVersion,
        List<Goal>               goalStackSnapshot,
        List<WorkingMemoryEntry> workingMemorySnapshot,
        List<Belief>             beliefSnapshot,
        int                      totalTokens,
        BigDecimal               totalCost,
        String                   integrityHash
    ) implements Snapshot {}

    // -------------------------------------------------------------------------
    // SHA-256 integrity hash over canonical state string
    // -------------------------------------------------------------------------
    private static String computeHash(String runId, RunState state, int cycle,
            List<Goal> goals, List<WorkingMemoryEntry> wm,
            List<Belief> beliefs, int tokens, BigDecimal cost) {
        try {
            String canonical = runId + "|" + state + "|" + cycle
                + "|goals=" + goals.stream().map(g -> g.id() + ":" + g.status()).collect(Collectors.joining(","))
                + "|wm="    + wm.stream().map(WorkingMemoryEntry::id).collect(Collectors.joining(","))
                + "|beliefs=" + beliefs.stream().map(b -> b.subject() + ":" + b.predicate()).collect(Collectors.joining(","))
                + "|tokens=" + tokens + "|cost=" + cost.toPlainString();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "hash-unavailable";
        }
    }
}
