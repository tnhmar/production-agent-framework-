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
 */
public class DefaultExecutionContext implements ExecutionContext {

    public static final String SNAPSHOT_SCHEMA_VERSION = "1.1";

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
    public void           transitionTo(RunState s) { this.state = Objects.requireNonNull(s); }
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
     */
    public void restoreFromSnapshot(Snapshot snap) {
        this.cycle          = snap.cycle();
        this.state          = snap.state();
        this.totalTokens.set(snap.totalTokens());
        synchronized (this) { this.totalCost = snap.totalCost(); }
        this.consFailures   = snap.consecutiveFailures();
        this.stagnantCycles = snap.stagnantCycles();
        this.stuckCycles    = snap.stuckCycles();
        this.revisions      = snap.revisionCount();
        snap.goalStackSnapshot().forEach(goalStack::push);
        snap.workingMemorySnapshot().forEach(workingMemory::add);
        snap.beliefSnapshot().forEach(beliefState::assertBelief);
    }

    // ── Static hash helpers ─────────────────────────────────────────

    public static String computeSnapshotHash(Snapshot snap) {
        return computeHash(
            snap.runId(), snap.state(), snap.cycle(),
            snap.goalStackSnapshot(),
            snap.workingMemorySnapshot(),
            snap.beliefSnapshot(),
            snap.totalTokens(), snap.totalCost(),
            snap.consecutiveFailures(), snap.stagnantCycles(),
            snap.stuckCycles(), snap.revisionCount());
    }

    private static String computeHash(
            String runId, RunState state, int cycle,
            List<Goal> goals, List<WorkingMemoryEntry> wm,
            List<Belief> beliefs, int tokens, BigDecimal cost,
            int consFailures, int stagnantCycles, int stuckCycles, int revisions) {
        try {
            String canonical = runId + "|" + state + "|" + cycle
                + "|goals=" + goals.stream()
                    .map(g -> g.id() + ":" + g.status())
                    .collect(Collectors.joining(","))
                + "|wm=" + wm.stream()
                    .map(e -> e.id() + ":" + contentHash(e.content()))
                    .collect(Collectors.joining(","))
                + "|beliefs=" + beliefs.stream()
                    .map(b -> b.subject() + ":" + b.predicate()
                        + ":" + contentHash(b.object())
                        + ":" + b.confidence())
                    .collect(Collectors.joining(","))
                + "|liveness=" + consFailures + ":" + stagnantCycles
                    + ":" + stuckCycles + ":" + revisions
                + "|tokens=" + tokens
                + "|cost=" + cost.toPlainString();

            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                "SHA-256 algorithm unavailable — JVM environment is non-compliant.", e);
        }
    }

    private static String contentHash(Object obj) {
        if (obj == null) return "null";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(obj.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── FullSnapshot record ────────────────────────────────────────────
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
        int                      consecutiveFailures,
        int                      stagnantCycles,
        int                      stuckCycles,
        int                      revisionCount,
        String                   integrityHash
    ) implements Snapshot {}
}
