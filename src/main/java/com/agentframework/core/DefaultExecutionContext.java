package com.agentframework.core;
import com.agentframework.foundation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
public class DefaultExecutionContext implements ExecutionContext {
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
    private final DefaultGoalStack    goalStack     = new DefaultGoalStack();
    private final DefaultWorkingMemory workingMemory = new DefaultWorkingMemory();
    private final DefaultBeliefState   beliefState   = new DefaultBeliefState();
    private final Map<String, JobToken> activeJobs   = new ConcurrentHashMap<>();
    private final List<CycleRecord>     trace        = Collections.synchronizedList(new ArrayList<>());
    private int        totalTokens  = 0;
    private BigDecimal totalCost    = BigDecimal.ZERO;
    private TerminationReason terminationReason;

    public DefaultExecutionContext(Task task, String tenantId, String userId) {
        this.task   = Objects.requireNonNull(task, "task");
        this.reqCtx = RequestContext.of(
            tenantId != null ? tenantId : "default",
            userId   != null ? userId   : "system");
    }

    public String runId()                    { return runId; }
    public String tenantId()                 { return reqCtx.tenantId(); }
    public Instant startTime()               { return startTime; }
    public RequestContext requestContext()   { return reqCtx; }
    public Task task()                       { return task; }
    public RunState currentState()           { return state; }
    public void transitionTo(RunState s)     { this.state = Objects.requireNonNull(s); }
    public int  cycleCount()                 { return cycle; }
    public void incrementCycle()             { cycle++; }
    public int  consecutiveFailures()        { return consFailures; }
    public void incrementConsecutiveFailures(){ consFailures++; }
    public void resetConsecutiveFailures()   { consFailures = 0; }
    public int  currentChainDepth()          { return chainDepth; }
    public void incrementChainDepth()        { chainDepth++; }
    public void resetChainDepth()            { chainDepth = 0; }
    public GoalStack     goalStack()         { return goalStack; }
    public WorkingMemory workingMemory()     { return workingMemory; }
    public BeliefState   beliefState()       { return beliefState; }
    public int  revisionCount()              { return revisions; }
    public void incrementRevisionCount()     { revisions++; }
    public boolean isRevisionBudgetExceeded(int max) { return revisions > max; }
    public void flagPlanStale(String hint)   { planStale = hint != null; stalenessHint = hint; }
    public boolean isPlanStale()             { return planStale; }
    public String  stalenessHint()           { return stalenessHint; }
    public Map<String, JobToken> activeJobs(){ return activeJobs; }
    public void recordCycle(CycleRecord r)   { trace.add(r); }
    public List<CycleRecord> trace()         { return new ArrayList<>(trace); }
    public int  totalTokensUsed()            { return totalTokens; }
    public void addTokens(int n)             { totalTokens += n; }
    public BigDecimal totalCost()            { return totalCost; }
    public void addCost(BigDecimal c)        { totalCost = totalCost.add(c); }
    public Optional<TerminationReason> terminationReason() { return Optional.ofNullable(terminationReason); }
    public void setTerminationReason(TerminationReason r)  { terminationReason = r; }
    public Snapshot checkpoint() { return new DefaultSnapshot(runId, state, cycle); }
    record DefaultSnapshot(String runId, RunState state, int cycle) implements Snapshot {}
}
