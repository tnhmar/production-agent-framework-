package com.agentframework.core;

import com.agentframework.foundation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Default, in-process implementation of {@link ExecutionContext}.
 *
 * <h3>Snapshot integrity</h3>
 * The SHA-256 hash stored in every {@link FullSnapshot} covers:
 * <ul>
 *   <li>Run identity: {@code runId}, {@code state}, {@code cycle}</li>
 *   <li>Goals: {@code id:status} for every goal on the stack</li>
 *   <li>Working memory: {@code id:contentHash} for every entry</li>
 *   <li>Beliefs: {@code subject:predicate:objectHash:confidence}</li>
 *   <li>Liveness counters: {@code consFailures}, {@code stagnantCycles},
 *       {@code stuckCycles}, {@code revisions}</li>
 *   <li>Accumulators: {@code totalTokens}, {@code totalCost}</li>
 * </ul>
 *
 * <p><b>N-EC-1 fix:</b> {@code totalTokens} is now an {@link AtomicInteger};
 * {@code addCost} is {@code synchronized} on {@code this}. Both accumulators
 * are safe under concurrent parallel tool execution.
 *
 * <p><b>C-2 fix:</b> {@link #transitionTo(RunState)} validates against the
 * legal transition table from Volume 1 and throws
 * {@link IllegalStateException} for any illegal transition, preventing
 * silent state machine corruption.
 */
public class DefaultExecutionContext implements ExecutionContext {

    public static final String SNAPSHOT_SCHEMA_VERSION = "1.1";

    /**
     * Legal state transitions per Volume 1 state-machine table.
     * Key = from-state, Value = allowed to-states.
     */
    private static final Map<RunState, Set<RunState>> LEGAL_TRANSITIONS;
    static {
        Map<RunState, Set<RunState>> m = new EnumMap<>(RunState.class);
        m.put(RunState.INITIALIZED, EnumSet.of(RunState.PLANNING, RunState.ABORTED));
        m.put(RunState.PLANNING,    EnumSet.of(RunState.EXECUTING, RunState.TERMINATED, RunState.ABORTED));
        m.put(RunState.EXECUTING,   EnumSet.of(RunState.PLANNING, RunState.COMPLETED, RunState.TERMINATED, RunState.ABORTED));
        m.put(RunState.COMPLETED,   Collections.emptySet());
        m.put(RunState.TERMINATED,  Collections.emptySet());
        m.put(RunState.ABORTED,     Collections.emptySet());
        LEGAL_TRANSITIONS = Collections.unmodifiableMap(m);
    }

    private final String         runId     = UUID.randomUUID().toString();
    private final RequestContext reqCtx;
    private final Instant        startTime = Instant.now();
    private final Task           task;
    private RunState   state        = RunState.INITIALIZED;
    private int        cycle        = 0;
    private int        consFailures = 0;
    private int        chainDepth   = 0;
    private int        revisions    = 0;
    private boolean    planStale    = false;
    private String     stalenessHint;
    private int        stagnantCycles = 0;
    private int        stuckCycles    = 0;

    private final DefaultGoalStack     goalStack     = new DefaultGoalStack();
    private final DefaultWorkingMemory workingMemory = new DefaultWorkingMemory();
    private final DefaultBeliefState   beliefState   = new DefaultBeliefState();
    private final Map<String, JobToken> activeJobs   = new ConcurrentHashMap<>();
    private final List<CycleRecord>     trace        = Collections.synchronizedList(new ArrayList<>());

    // N-EC-1 fix: thread-safe accumulators
    private final AtomicInteger  totalTokens = new AtomicInteger(0);
    private       BigDecimal     totalCost   = BigDecimal.ZERO;

    private TerminationReason terminationReason;

    public DefaultExecutionContext(Task task, String tenantId, String userId) {
        this.task   = Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(tenantId,
            "tenantId must not be null — pass an explicit tenant ID.");
        this.reqCtx = RequestContext.of(tenantId, userId != null ? userId : "system");
    }

    // ── Core accessors ───────────────────────────────────────────────
    public String         runId()              { return runId; }
    public String         tenantId()           { return reqCtx.tenantId(); }
    public Instant        startTime()          { return startTime; }
    public RequestContext requestContext()      { return reqCtx; }
    public Task           task()               { return task; }
    public RunState       currentState()       { return state; }

    /**
     * C-2 fix: validated transition. Rejects any state change not listed
     * in the Volume 1 legal-transition table.
     */
    public void transitionTo(RunState next) {
        Objects.requireNonNull(next, "target state must not be null");
        Set<RunState> allowed = LEGAL_TRANSITIONS.getOrDefault(state, Collections.emptySet());
        if (!allowed.contains(next)) {
            throw new IllegalStateException(
                "Illegal state transition: " + state + " → " + next +
                ". Allowed from " + state + ": " + allowed);
        }
        this.state = next;
    }

    public int            cycleCount()         { return cycle; }
    public void           incrementCycle()     { cycle++; }
    public int            consecutiveFailures(){ return consFailures; }
    public void  incrementConsecutiveFailures(){ consFailures++; }
    public void  resetConsecutiveFailures()    { consFailures = 0; }
    public int   currentChainDepth()           { return chainDepth; }
    public void  incrementChainDepth()         { chainDepth++; }
    public void  resetChainDepth()             { chainDepth = 0; }

    // ── Liveness counters ───────────────────────────────────────────
    public int  stagnantCycles()          { return stagnantCycles; }
    public void incrementStagnantCycles() { stagnantCycles++; }
    public void resetStagnantCycles()     { stagnantCycles = 0; }
    public int  stuckCycles()             { return stuckCycles; }
    public void incrementStuckCycles()    { stuckCycles++; }
    public void resetStuckCycles()        { stuckCycles = 0; }

    // ── Goal / memory / belief ──────────────────────────────────────
    public GoalStack     goalStack()     { return goalStack; }
    public WorkingMemory workingMemory() { return workingMemory; }
    public BeliefState   beliefState()   { return beliefState; }

    // ── Revision / plan staleness ──────────────────────────────────
    public int     revisionCount()             { return revisions; }
    public void    incrementRevisionCount()    { revisions++; }
    public boolean isRevisionBudgetExceeded(int max) { return revisions > max; }
    public void    flagPlanStale(String hint)  { planStale = hint != null; stalenessHint = hint; }
    public boolean isPlanStale()               { return planStale; }
    public String  stalenessHint()             { return stalenessHint; }

    // ── Jobs / trace / resources ────────────────────────────────────
    public Map<String, JobToken> activeJobs()  { return activeJobs; }
    public void    recordCycle(CycleRecord r)  { trace.add(r); }
    public List<CycleRecord> trace()           { return new ArrayList<>(trace); }

    /** N-EC-1 fix: atomic read. */
    public int        totalTokensUsed()        { return totalTokens.get(); }

    /** N-EC-1 fix: atomic increment. */
    public void       addTokens(int n)         { totalTokens.addAndGet(n); }

    /** N-EC-1 fix: synchronised BigDecimal accumulation. */
    public synchronized BigDecimal totalCost() { return totalCost; }
    public synchronized void addCost(BigDecimal c) { totalCost = totalCost.add(c); }

    public Optional<TerminationReason> terminationReason() { return Optional.ofNullable(terminationReason); }
    public void setTerminationReason(TerminationReason r)  { terminationReason = r; }

    // ── Checkpoint ────────────────────────────────────────────────
    @Override
    public Snapshot checkpoint() {
        List<Goal>               goals   = goalStack.all();
        List<WorkingMemoryEntry> wm      = workingMemory.getAll();
        List<Belief>             beliefs = beliefState.all(0.0);
        String hash = computeHash(
            runId, state, cycle,
            goals, wm, beliefs,
            totalTokens.get(), totalCost(),
            consFailures, stagnantCycles, stuckCycles, revisions);
        return new FullSnapshot(
            runId, state, cycle,
            SNAPSHOT_SCHEMA_VERSION,
            Collections.unmodifiableList(new ArrayList<>(goals)),
            Collections.unmodifiableList(new ArrayList<>(wm)),
            Collections.unmodifiableList(new ArrayList<>(beliefs)),
            totalTokens.get(), totalCost(),
            consFailures, stagnantCycles, stuckCycles, revisions,
            hash);
    }

    /**
     * Restores all mutable execution state from a persisted snapshot.
     * Uses an unchecked internal setter to bypass the transition guard —
     * replay is the only legitimate path that may land in any state.
     */
    public void restoreFromSnapshot(Snapshot snap) {
        this.cycle          = snap.cycle();
        this.state          = snap.state();   // direct field set — bypass guard intentionally
        this.totalTokens.set(snap.totalTokens());
        synchronized (this) { this.totalCost = snap.totalCost(); }
        this.consFailures   = snap.consecutiveFailures();
        this.stagnantCycles = snap.stagnantCycles();
        this.stuckCycles    = snap.stuckCycles();
        this.revisions      = snap.revisionCount();
        snap.goalStackSnapshot().forEach(goalStack::push);
        snap.workingMemorySnapshot().forEach(workingMemory::add);
        snap.beliefSnapshot().forEach(bel ->
            beliefState.assertBelief(bel.subject(), bel.predicate(), bel.object(),
                                     bel.confidence(), bel.provenance()));
    }

    // ── Hash computation ─────────────────────────────────────────────
    static String computeHash(
            String runId, RunState state, int cycle,
            List<Goal> goals, List<WorkingMemoryEntry> wm, List<Belief> beliefs,
            int tokens, BigDecimal cost,
            int consFailures, int stagnant, int stuck, int revisions) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(runId).append('|').append(state).append('|').append(cycle).append('|');
        goals.stream()
             .sorted(Comparator.comparing(Goal::id))
             .forEach(g -> sb.append(g.id()).append(':').append(g.status()).append(','));
        sb.append('|');
        wm.stream()
          .sorted(Comparator.comparing(WorkingMemoryEntry::id))
          .forEach(e -> sb.append(e.id()).append(':').append(e.content().hashCode()).append(','));
        sb.append('|');
        beliefs.stream()
               .sorted(Comparator.comparing(b -> b.subject() + b.predicate()))
               .forEach(b -> sb.append(b.subject()).append(':').append(b.predicate())
                               .append(':').append(b.object().hashCode())
                               .append(':').append(b.confidence()).append(','));
        sb.append('|').append(consFailures).append('|').append(stagnant)
          .append('|').append(stuck).append('|').append(revisions)
          .append('|').append(tokens).append('|').append(cost);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
