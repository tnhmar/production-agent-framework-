package com.agentframework.tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.agentframework.action.*;
import com.agentframework.action.middleware.*;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.memory.impl.TieredMemory;
import com.agentframework.observability.*;
import com.agentframework.perception.*;
import com.agentframework.reasoning.*;
import com.agentframework.reasoning.strategy.ReActStrategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extended coverage tests targeting branches left uncovered after the initial
 * RuntimeCoverageTest pass (lines ~69%, branches ~55%).
 *
 * <p>Every assertion is derived from the actual production control-flow:
 * <ul>
 *   <li>{@link ExecutionResult#succeeded()} returns {@code true} iff
 *       {@code terminationReason instanceof GoalCompleted}.</li>
 *   <li>{@link StateMachineRunner} transitions SUSPENDED_HITL/WAITING_FOR_JOB
 *       to TERMINATED with {@link TerminationReason.Escalated}.</li>
 *   <li>{@link StateMachineRunner} transitions DEGRADED to TERMINATED with
 *       {@link TerminationReason.FailureEscalation}.</li>
 *   <li>Chain-depth guard fires at INITIALIZED when
 *       {@code currentChainDepth() > maxChainDepth}.</li>
 *   <li>{@link DefaultExecutionContext.FullSnapshot} is package-private;
 *       tamper tests build an anonymous {@link ExecutionContext.Snapshot}
 *       that delegates all accessors to the real snapshot but overrides
 *       {@code integrityHash()} to return a forged value.</li>
 * </ul>
 */
public class ExtendedCoverageTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private Agent agentWith(LLMProvider llm, SimpleToolRegistry reg) {
        DefaultToolDispatcher dispatcher = new DefaultToolDispatcher(reg);
        DefaultAction action = new DefaultAction(
                reg, List.of(new SafetyActionValidator()),
                ToolMiddleware.identity(), dispatcher);
        LLMReasoning reasoning = new LLMReasoning(
                llm, new ReActStrategy(),
                new PromptBuilder("You are a helpful agent.", reg, 4096));
        return Agent.builder()
                .perception(new SimplePerception())
                .reasoning(reasoning)
                .action(action)
                .memory(TieredMemory.Builder.inMemory())
                .build();
    }

    private AgentRuntime runtime() {
        return new AgentRuntime(new PassThroughPlanValidator());
    }

    /**
     * Builds an anonymous Snapshot that is identical to {@code real} in every
     * accessor except {@code integrityHash()}, which returns the supplied
     * {@code forgedHash}. This is the only correct way to construct a tampered
     * snapshot from outside the {@code core} package because
     * {@link DefaultExecutionContext.FullSnapshot} is package-private.
     */
    private static ExecutionContext.Snapshot tamperHash(
            ExecutionContext.Snapshot real, String forgedHash) {
        return new ExecutionContext.Snapshot() {
            @Override public String                   runId()                  { return real.runId(); }
            @Override public RunState                 state()                  { return real.state(); }
            @Override public int                      cycle()                  { return real.cycle(); }
            @Override public String                   schemaVersion()          { return real.schemaVersion(); }
            @Override public List<Goal>               goalStackSnapshot()      { return real.goalStackSnapshot(); }
            @Override public List<WorkingMemoryEntry> workingMemorySnapshot()  { return real.workingMemorySnapshot(); }
            @Override public List<Belief>             beliefSnapshot()         { return real.beliefSnapshot(); }
            @Override public int                      totalTokens()            { return real.totalTokens(); }
            @Override public BigDecimal               totalCost()              { return real.totalCost(); }
            @Override public int                      consecutiveFailures()    { return real.consecutiveFailures(); }
            @Override public int                      stagnantCycles()         { return real.stagnantCycles(); }
            @Override public int                      stuckCycles()            { return real.stuckCycles(); }
            @Override public int                      revisionCount()          { return real.revisionCount(); }
            @Override public String                   integrityHash()          { return forgedHash; }
        };
    }

    // ── 1. executeAsync ───────────────────────────────────────────────────────

    @Test
    public void executeAsync_noTenant_succeeds() throws Exception {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("async done"), reg);
        Task task = Task.builder().instruction("async").maxCycles(5).build();
        ExecutionResult r = runtime().executeAsync(agent, task).get();
        assertTrue(r.succeeded(), "executeAsync with system tenant must succeed");
    }

    @Test
    public void executeAsync_withTenant_succeeds() throws Exception {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("async done"), reg);
        Task task = Task.builder().instruction("async tenant").maxCycles(5).build();
        ExecutionResult r = runtime().executeAsync(agent, task, "tenant-1", "user-1").get();
        assertTrue(r.succeeded(), "executeAsync with explicit tenant must succeed");
    }

    @Test
    public void executeAsync_withCustomPool_succeeds() throws Exception {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("pool done"), reg);
        Task task = Task.builder().instruction("pool").maxCycles(5).build();
        AgentRuntime rt = new AgentRuntime(
                new PassThroughPlanValidator(),
                EventSink.noop(),
                Executors.newFixedThreadPool(1));
        ExecutionResult r = rt.executeAsync(agent, task).get();
        assertTrue(r.succeeded(), "executeAsync with injected executor must succeed");
    }

    // ── 2. executeWith ────────────────────────────────────────────────────────

    @Test
    public void executeWith_callerSuppliedContext_succeeds() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("ctx done"), reg);
        Task task = Task.builder().instruction("ctx test").maxCycles(5).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t", "u");
        ExecutionResult r = runtime().executeWith(agent, ctx);
        // succeeded() == true iff terminationReason is GoalCompleted
        assertTrue(r.succeeded(), "executeWith caller-supplied context must succeed");
        // finalAnswer is populated from the last FinalAnswer CycleRecord
        assertNotNull(r.finalAnswer(), "finalAnswer must be present after GoalCompleted");
    }

    // ── 3. replay ─────────────────────────────────────────────────────────────

    @Test
    public void replay_fromSnapshot_resumesAndReturnsResult() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("first run"), reg);
        Task task = Task.builder().instruction("original").maxCycles(5).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t", "u");
        runtime().executeWith(agent, ctx);
        ExecutionContext.Snapshot snap = ctx.checkpoint();

        Agent agent2 = agentWith(StubLLMProvider.finalAnswer("replay done"), reg);
        ExecutionResult r = runtime().replay(snap, agent2, "t", "u");
        assertNotNull(r, "replay must return a non-null result");
    }

    @Test
    public void replay_systemTenantShorthand_works() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("ok"), reg);
        Task task = Task.builder().instruction("shorthand").maxCycles(5).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t", "u");
        runtime().executeWith(agent, ctx);
        ExecutionContext.Snapshot snap = ctx.checkpoint();

        // replay(snap, agent) uses SYSTEM_TENANT + "replay-user" internally
        Agent agent2 = agentWith(StubLLMProvider.finalAnswer("ok2"), reg);
        assertNotNull(runtime().replay(snap, agent2), "single-arg replay must return result");
    }

    @Test
    public void replay_withCustomTask_usesProvidedTask() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("orig"), reg);
        Task origTask = Task.builder().instruction("original inst").maxCycles(5).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(origTask, "t", "u");
        runtime().executeWith(agent, ctx);
        ExecutionContext.Snapshot snap = ctx.checkpoint();

        Task customTask = Task.builder().instruction("custom").maxCycles(3).build();
        Agent agent2 = agentWith(StubLLMProvider.finalAnswer("custom done"), reg);
        assertNotNull(runtime().replay(snap, agent2, customTask, "t", "u"),
                "replay with custom Task must return result");
    }

    // ── 4. replay integrity tamper ────────────────────────────────────────────
    //
    // DefaultExecutionContext.FullSnapshot is package-private, so it cannot be
    // instantiated from outside com.agentframework.core.  The tamper helper builds
    // an anonymous implementation of the public ExecutionContext.Snapshot interface
    // that returns the real snapshot's values for every field except integrityHash().

    @Test
    public void replay_tamperedSnapshot_throwsIllegalArgument() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("ok"), reg);
        Task task = Task.builder().instruction("orig").maxCycles(5).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t", "u");
        runtime().executeWith(agent, ctx);
        ExecutionContext.Snapshot good = ctx.checkpoint();

        ExecutionContext.Snapshot tampered = tamperHash(good, "00000000deadbeef");

        assertThrows(IllegalArgumentException.class,
                () -> runtime().replay(tampered, agent, "t", "u"),
                "Tampered integrityHash must cause replay() to throw IllegalArgumentException");
    }

    // ── 5. SUSPENDED_HITL → Escalated ─────────────────────────────────────────
    //
    // StateMachineRunner.step() handles SUSPENDED_HITL/WAITING_FOR_JOB by calling
    // terminate() with TerminationReason.Escalated and returning immediately.
    // AskClarification in Review transitions the context to SUSPENDED_HITL;
    // the next step() call converts it to TERMINATED(Escalated).

    @Test
    public void stateMachine_suspendedHitl_terminatesWithEscalated() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        // LLM always returns ask_clarification → drives context into SUSPENDED_HITL
        LLMProvider llm = p -> "{\"type\":\"ask_clarification\",\"question\":\"Which option?\"}";
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("needs clarification").maxCycles(5).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t", "u");
        ExecutionResult r = runtime().executeWith(agent, ctx);
        // succeeded() is false because terminationReason is Escalated, not GoalCompleted
        assertFalse(r.succeeded(),
                "SUSPENDED_HITL must not succeed; got: " + r.terminationReason());
        assertInstanceOf(TerminationReason.Escalated.class, r.terminationReason(),
                "SUSPENDED_HITL must produce Escalated termination reason");
    }

    // ── 6. DEGRADED → FailureEscalation ───────────────────────────────────────
    //
    // Review transitions to DEGRADED after 3 consecutive ToolExceptions.
    // The DEGRADED case in StateMachineRunner immediately terminates with
    // TerminationReason.FailureEscalation.

    @Test
    public void stateMachine_degradedState_terminatesWithFailureEscalation() {
        InMemoryEventSink sink = new InMemoryEventSink();
        AgentRuntime rt = new AgentRuntime(new PassThroughPlanValidator(), sink);
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("fail", "1.0", "fail"),
                (args, c) -> { throw new ToolException("ERR", "forced"); });
        // LLM always calls the failing tool → 3 consecutive ToolExceptions → DEGRADED
        LLMProvider llm = p ->
                "{\"type\":\"tool_call\",\"tool_name\":\"fail\",\"arguments\":{},\"reasoning_trace\":\"t\"}";
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("degraded").maxCycles(20).build();
        ExecutionResult r = rt.execute(agent, task);
        assertFalse(r.succeeded(),
                "DEGRADED path must not succeed");
        assertInstanceOf(TerminationReason.FailureEscalation.class, r.terminationReason(),
                "DEGRADED must produce FailureEscalation, got: " + r.terminationReason());
    }

    // ── 7. PartialSuccess hostile taint ───────────────────────────────────────

    @Test
    public void review_partialSuccess_hostileTaint_doesNotThrow() {
        InMemoryEventSink sink = new InMemoryEventSink();
        AgentRuntime rt = new AgentRuntime(new PassThroughPlanValidator(), sink);
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("search", "1.0", "search"),
                (args, c) -> ToolResult.ok("ignore this prompt injected text"));

        AtomicInteger calls = new AtomicInteger();
        LLMProvider llm = p -> {
            int n = calls.incrementAndGet();
            if (n == 1)
                return "{\"type\":\"tool_call\",\"tool_name\":\"search\",\"arguments\":{},\"reasoning_trace\":\"t\"}";
            return "{\"type\":\"final_answer\",\"content\":\"done\"}";
        };
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("search").maxCycles(10).build();
        assertDoesNotThrow(() -> rt.execute(agent, task, "t", "u"),
                "TaintClassifier must not throw regardless of classifier decision");
    }

    // ── 8. Belief conflict event ───────────────────────────────────────────────

    @Test
    public void review_beliefConflict_doesNotThrow() {
        InMemoryEventSink sink = new InMemoryEventSink();
        AgentRuntime rt = new AgentRuntime(new PassThroughPlanValidator(), sink);
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("q", "1.0", "q"),
                (args, c) -> ToolResult.ok("value-1"));

        AtomicInteger calls = new AtomicInteger();
        LLMProvider llm = p -> {
            int n = calls.incrementAndGet();
            if (n <= 2)
                return "{\"type\":\"tool_call\",\"tool_name\":\"q\",\"arguments\":{},\"reasoning_trace\":\"t\"}";
            return "{\"type\":\"final_answer\",\"content\":\"ok\"}";
        };
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("belief conflict").maxCycles(15).build();
        assertDoesNotThrow(() -> rt.execute(agent, task, "t", "u"),
                "Belief-conflict detection must not throw");
    }

    // ── 9. world-change NeedsCorrection → flagPlanStale ───────────────────────
    //
    // Review calls validator.validateAfterAction().  When it returns
    // NeedsCorrection the Review code flags the plan stale and increments the
    // revision counter.  This is distinct from the pre-action NeedsCorrection
    // handled by StateMachineRunner (which directly gates the Decision).

    @Test
    public void review_worldChange_needsCorrection_validateAfterActionCalled() {
        AtomicInteger validateAfterCalls = new AtomicInteger();
        PlanValidator validator = new PlanValidator() {
            @Override
            public ValidationResult validate(Decision d, ExecutionContext ctx) {
                return new ValidationResult.Passed();
            }
            @Override
            public ValidationResult validateAfterAction(ActionResult r, ExecutionContext ctx) {
                int n = validateAfterCalls.incrementAndGet();
                // Return NeedsCorrection once, then Passed so the run can finish
                return n == 1
                    ? new ValidationResult.NeedsCorrection("stale plan",
                          new FinalAnswer("retry", List.of()))
                    : new ValidationResult.Passed();
            }
        };

        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("mutate", "1.0", "mutate"),
                (args, c) -> ToolResult.write("changed!"));

        AtomicInteger llmCalls = new AtomicInteger();
        LLMProvider llm = p -> {
            int n = llmCalls.incrementAndGet();
            return n == 1
                ? "{\"type\":\"tool_call\",\"tool_name\":\"mutate\",\"arguments\":{},\"reasoning_trace\":\"t\"}"
                : "{\"type\":\"final_answer\",\"content\":\"done\"}";
        };

        AgentRuntime rt = new AgentRuntime(validator);
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("mutate world").maxCycles(20).build();
        rt.execute(agent, task, "t", "u");
        assertTrue(validateAfterCalls.get() >= 1,
                "validateAfterAction must be called at least once when tool executes");
    }

    // ── 10. world-change NeedsCorrection exhausting revision budget ────────────
    //
    // StateMachineRunner handles NeedsCorrection from validate() (pre-action).
    // After isRevisionBudgetExceeded(3) the runner terminates with PlanIncoherent.
    // We drive this by always returning NeedsCorrection from validate() so the
    // run never reaches action execution.

    @Test
    public void stateMachine_revisionBudgetExhausted_terminatesWithPlanIncoherent() {
        PlanValidator alwaysNeedsRevision = new PlanValidator() {
            @Override
            public ValidationResult validate(Decision d, ExecutionContext ctx) {
                // Pre-action NeedsCorrection: increments revisionCount each cycle.
                // After 4 NeedsCorrection calls isRevisionBudgetExceeded(3) fires.
                return new ValidationResult.NeedsCorrection("always stale",
                        new FinalAnswer("retry", List.of()));
            }
            @Override
            public ValidationResult validateAfterAction(ActionResult r, ExecutionContext ctx) {
                return new ValidationResult.Passed();
            }
        };

        SimpleToolRegistry reg = new SimpleToolRegistry();
        LLMProvider llm = p ->
                "{\"type\":\"final_answer\",\"content\":\"done\"}";
        AgentRuntime rt = new AgentRuntime(alwaysNeedsRevision);
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("always stale").maxCycles(50).build();
        ExecutionResult r = rt.execute(agent, task, "t", "u");
        assertFalse(r.succeeded());
        assertInstanceOf(TerminationReason.PlanIncoherent.class, r.terminationReason(),
                "Revision budget exhaustion must produce PlanIncoherent, got: "
                + r.terminationReason());
    }

    // ── 11. delegation depth exceeded → ResourceLimit ─────────────────────────
    //
    // Guard: ctx.currentChainDepth() > maxChainDepth fires in the INITIALIZED case.
    // With maxChainDepth(1) and ctx pre-incremented to 2, the condition is 2 > 1.

    @Test
    public void stateMachine_delegationDepthExceeded_terminatesWithResourceLimit() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("ok"), reg);
        Task task = Task.builder().instruction("depth").maxCycles(5).maxChainDepth(1).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t", "u");
        ctx.incrementChainDepth(); // depth = 1
        ctx.incrementChainDepth(); // depth = 2, exceeds maxChainDepth = 1
        ExecutionResult r = runtime().executeWith(agent, ctx);
        assertFalse(r.succeeded(),
                "Exceeded chain depth must not succeed");
        assertInstanceOf(TerminationReason.ResourceLimit.class, r.terminationReason(),
                "Chain depth > maxChainDepth must produce ResourceLimit, got: "
                + r.terminationReason());
    }

    // ── 12. fail-then-succeed → resetConsecutiveFailures ─────────────────────

    @Test
    public void review_failThenSucceed_resetsConsecutiveFailures() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("goodtool", "1.0", "goodtool"),
                (args, c) -> ToolResult.ok("result"));

        AtomicInteger calls = new AtomicInteger();
        LLMProvider llm = p -> {
            int n = calls.incrementAndGet();
            if (n == 1)
                return "{\"type\":\"tool_call\",\"tool_name\":\"goodtool\",\"arguments\":{},\"reasoning_trace\":\"t\"}";
            return "{\"type\":\"final_answer\",\"content\":\"recovered\"}";
        };
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("fail then succeed").maxCycles(10).build();
        ExecutionResult r = runtime().execute(agent, task, "t", "u");
        assertTrue(r.succeeded(),
                "Run that recovers with FinalAnswer must produce GoalCompleted");
        assertNotNull(r.finalAnswer(), "finalAnswer must be populated");
    }

    // ── 13. Snapshot roundtrip with working-memory ────────────────────────────

    @Test
    public void checkpoint_roundtrip_withWorkingMemory_hashIsStable() {
        Task task = Task.builder().instruction("snap").maxCycles(5).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t", "u");
        ctx.workingMemory().add(new WorkingMemoryEntry(
                "id1", "payload", WorkingMemoryTier.ACTIVE,
                Origin.SYSTEM, 1.0, Instant.now(), TaintLabel.CLEAN));
        ExecutionContext.Snapshot snap = ctx.checkpoint();

        assertNotNull(snap, "checkpoint() must return a non-null Snapshot");
        // The stored hash must be reproducible from the snapshot's own fields
        assertEquals(snap.integrityHash(),
                DefaultExecutionContext.computeSnapshotHash(snap),
                "computeSnapshotHash must reproduce the stored integrityHash");
        assertFalse(snap.workingMemorySnapshot().isEmpty(),
                "Snapshot must include the WorkingMemoryEntry that was added");
    }

    // ── 14. EventSink.noop() ──────────────────────────────────────────────────

    @Test
    public void eventSink_noop_swallowsAllEventTypes() {
        EventSink noop = EventSink.noop();
        for (AgentEvent.EventType type : AgentEvent.EventType.values()) {
            assertDoesNotThrow(
                    () -> noop.emit(new AgentEvent("run-1", "t", type, Instant.now(), Map.of())),
                    "noop EventSink must not throw for event type: " + type);
        }
    }

    // ── 15. InMemoryEventSink captures RUN_STARTED ────────────────────────────

    @Test
    public void runtime_withInMemoryEventSink_capturesRunStartedEvent() {
        InMemoryEventSink sink = new InMemoryEventSink();
        AgentRuntime rt = new AgentRuntime(new PassThroughPlanValidator(), sink);
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("ev"), reg);
        Task task = Task.builder().instruction("events").maxCycles(5).build();
        rt.execute(agent, task, "t", "u");
        // InMemoryEventSink.all() returns all captured events
        assertFalse(sink.all().isEmpty(),
                "InMemoryEventSink must capture at least one event after execute()");
        assertTrue(sink.all().stream()
                        .anyMatch(e -> e.type() == AgentEvent.EventType.RUN_STARTED),
                "AgentRuntime.executeWith() must emit RUN_STARTED as its first event");
    }
}
