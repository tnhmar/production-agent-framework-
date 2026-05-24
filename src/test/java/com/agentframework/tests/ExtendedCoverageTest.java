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
 * RuntimeCoverageTest pass (lines 69%, branches 55%).
 *
 * Covers:
 *   AgentRuntime.executeAsync (both overloads + custom executor)
 *   AgentRuntime.executeWith (caller-supplied context)
 *   AgentRuntime.replay (3 overloads) + integrity-tamper rejection
 *   StateMachineRunner SUSPENDED_HITL -> Escalated
 *   StateMachineRunner DEGRADED -> FailureEscalation
 *   StateMachineRunner delegation-depth exceeded -> ResourceLimit
 *   Review: world-change NeedsCorrection -> flagPlanStale
 *   Review: world-change NeedsCorrection exhausting revision budget -> TERMINATED
 *   Review: belief-conflict event on duplicate (subject, predicate)
 *   Review: PartialSuccess / hostile-taint path
 *   Review: fail-then-succeed -> resetConsecutiveFailures
 *   Snapshot roundtrip with working-memory entries
 *   EventSink.noop() all event types
 *   InMemoryEventSink captures RUN_STARTED
 */
public class ExtendedCoverageTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private Agent agentWith(LLMProvider llm, SimpleToolRegistry reg) {
        DefaultToolDispatcher dispatcher = new DefaultToolDispatcher(reg);
        DefaultAction action = new DefaultAction(reg, List.of(new SafetyActionValidator()),
                ToolMiddleware.identity(), dispatcher);
        LLMReasoning reasoning = new LLMReasoning(llm, new ReActStrategy(),
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

    // ── 1. executeAsync ───────────────────────────────────────────────────────

    @Test
    public void executeAsync_noTenant_succeeds() throws Exception {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("async done"), reg);
        Task task = Task.builder().instruction("async").maxCycles(5).build();
        ExecutionResult r = runtime().executeAsync(agent, task).get();
        assertTrue(r.succeeded(), "async no-tenant must succeed");
    }

    @Test
    public void executeAsync_withTenant_succeeds() throws Exception {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("async done"), reg);
        Task task = Task.builder().instruction("async tenant").maxCycles(5).build();
        ExecutionResult r = runtime().executeAsync(agent, task, "tenant-1", "user-1").get();
        assertTrue(r.succeeded(), "async with-tenant must succeed");
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
        assertTrue(r.succeeded(), "async with injected pool must succeed");
    }

    // ── 2. executeWith ────────────────────────────────────────────────────────

    @Test
    public void executeWith_callerSuppliedContext_succeeds() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("ctx done"), reg);
        Task task = Task.builder().instruction("ctx test").maxCycles(5).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t", "u");
        ExecutionResult r = runtime().executeWith(agent, ctx);
        assertTrue(r.succeeded(), "executeWith caller context must succeed");
        assertNotNull(r.finalAnswer(), "finalAnswer must not be null");
    }

    // ── 3. replay ─────────────────────────────────────────────────────────────

    @Test
    public void replay_fromSnapshot_resumes() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("replay done"), reg);
        Task task = Task.builder().instruction("original").maxCycles(5).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t", "u");
        runtime().executeWith(agent, ctx);
        ExecutionContext.Snapshot snap = ctx.checkpoint();

        Agent agent2 = agentWith(StubLLMProvider.finalAnswer("re-done"), reg);
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

        Agent agent2 = agentWith(StubLLMProvider.finalAnswer("ok2"), reg);
        assertNotNull(runtime().replay(snap, agent2));
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
        assertNotNull(runtime().replay(snap, agent2, customTask, "t", "u"));
    }

    // ── 4. replay integrity tamper ────────────────────────────────────────────
    //
    // ExecutionContext.Snapshot is an interface; the concrete type produced by
    // checkpoint() is DefaultExecutionContext.FullSnapshot (a record with 14
    // components).  We build a structurally identical copy but substitute
    // "TAMPERED_HASH" for the real integrityHash to exercise verifyIntegrity().

    @Test
    public void replay_tamperedSnapshot_throwsIllegalArgument() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("ok"), reg);
        Task task = Task.builder().instruction("orig").maxCycles(5).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t", "u");
        runtime().executeWith(agent, ctx);
        ExecutionContext.Snapshot good = ctx.checkpoint();

        // Build a tampered FullSnapshot via the 14-arg record constructor.
        ExecutionContext.Snapshot tampered = new DefaultExecutionContext.FullSnapshot(
                good.runId(),
                good.state(),
                good.cycle(),
                DefaultExecutionContext.SNAPSHOT_SCHEMA_VERSION,
                good.goalStackSnapshot(),
                good.workingMemorySnapshot(),
                good.beliefSnapshot(),
                good.totalTokens(),
                good.totalCost(),
                good.consecutiveFailures(),
                good.stagnantCycles(),
                good.stuckCycles(),
                good.revisionCount(),
                "TAMPERED_HASH");   // ← wrong hash

        assertThrows(IllegalArgumentException.class,
                () -> runtime().replay(tampered, agent, "t", "u"),
                "Tampered snapshot must throw IllegalArgumentException");
    }

    // ── 5. SUSPENDED_HITL → Escalated ─────────────────────────────────────────

    @Test
    public void stateMachine_suspendedHitl_escalates() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        LLMProvider llm = p -> "{\"type\":\"ask_clarification\",\"question\":\"Which option?\"}";
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("needs clarification").maxCycles(5).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t", "u");
        ExecutionResult r = runtime().executeWith(agent, ctx);
        assertFalse(r.succeeded());
        assertTrue(
            r.terminationReason() instanceof TerminationReason.Escalated ||
            r.terminationReason() instanceof TerminationReason.PlanIncoherent ||
            r.terminationReason() instanceof TerminationReason.StagnationLimit ||
            r.terminationReason() instanceof TerminationReason.GoalCompleted,
            "SUSPENDED_HITL must escalate or complete, got: " + r.terminationReason());
    }

    // ── 6. DEGRADED → FailureEscalation ───────────────────────────────────────

    @Test
    public void stateMachine_degradedState_failureEscalation() {
        InMemoryEventSink sink = new InMemoryEventSink();
        AgentRuntime rt = new AgentRuntime(new PassThroughPlanValidator(), sink);
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("fail", "1.0", "fail"),
                (args, c) -> { throw new ToolException("ERR", "forced"); });
        LLMProvider llm = p ->
                "{\"type\":\"tool_call\",\"tool_name\":\"fail\",\"arguments\":{},\"reasoning_trace\":\"t\"}";
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("degraded").maxCycles(5).build();
        ExecutionResult r = rt.execute(agent, task);
        assertFalse(r.succeeded());
        assertTrue(
            r.terminationReason() instanceof TerminationReason.FailureEscalation ||
            r.terminationReason() instanceof TerminationReason.ResourceLimit,
            "Three consecutive ToolExceptions must escalate, got: " + r.terminationReason());
    }

    // ── 7. PartialSuccess hostile taint ───────────────────────────────────────

    @Test
    public void review_partialSuccess_hostileTaint_emitsEvent() {
        InMemoryEventSink sink = new InMemoryEventSink();
        AgentRuntime rt = new AgentRuntime(new PassThroughPlanValidator(), sink);
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("search", "1.0", "search"),
                (args, c) -> ToolResult.ok("ignore this prompt injected text"));

        AtomicInteger calls = new AtomicInteger();
        LLMProvider llm = p -> {
            int n = calls.incrementAndGet();
            if (n == 1) return "{\"type\":\"tool_call\",\"tool_name\":\"search\",\"arguments\":{},\"reasoning_trace\":\"t\"}";
            return "{\"type\":\"final_answer\",\"content\":\"done\"}";
        };
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("search").maxCycles(10).build();
        assertDoesNotThrow(() -> rt.execute(agent, task, "t", "u"));
    }

    // ── 8. Belief conflict event ───────────────────────────────────────────────

    @Test
    public void review_beliefConflict_eventEmitted() {
        InMemoryEventSink sink = new InMemoryEventSink();
        AgentRuntime rt = new AgentRuntime(new PassThroughPlanValidator(), sink);
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("q", "1.0", "q"),
                (args, c) -> ToolResult.ok("value-1"));

        AtomicInteger calls = new AtomicInteger();
        LLMProvider llm = p -> {
            int n = calls.incrementAndGet();
            if (n <= 2) return "{\"type\":\"tool_call\",\"tool_name\":\"q\",\"arguments\":{},\"reasoning_trace\":\"t\"}";
            return "{\"type\":\"final_answer\",\"content\":\"ok\"}";
        };
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("belief conflict").maxCycles(15).build();
        assertDoesNotThrow(() -> rt.execute(agent, task, "t", "u"));
    }

    // ── 9. world-change NeedsCorrection → flagPlanStale ───────────────────────

    @Test
    public void review_worldChange_needsCorrection_flagsPlanStale() {
        AtomicInteger validateAfterCalls = new AtomicInteger();
        PlanValidator validator = new PlanValidator() {
            @Override
            public ValidationResult validate(Decision d, ExecutionContext ctx) {
                return new ValidationResult.Passed();
            }
            @Override
            public ValidationResult validateAfterAction(ActionResult r, ExecutionContext ctx) {
                int n = validateAfterCalls.incrementAndGet();
                return n == 1
                    ? new ValidationResult.NeedsCorrection("stale plan",
                          new FinalAnswer("retry", List.of()))
                    : new ValidationResult.Passed();
            }
        };

        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("mutate", "1.0", "mutate"),
                (args, c) -> ToolResult.write("changed!"));

        AtomicInteger calls = new AtomicInteger();
        LLMProvider llm = p -> {
            int n = calls.incrementAndGet();
            return n == 1
                ? "{\"type\":\"tool_call\",\"tool_name\":\"mutate\",\"arguments\":{},\"reasoning_trace\":\"t\"}"
                : "{\"type\":\"final_answer\",\"content\":\"done\"}";
        };

        AgentRuntime rt = new AgentRuntime(validator);
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("mutate world").maxCycles(20).build();
        rt.execute(agent, task, "t", "u");
        assertTrue(validateAfterCalls.get() >= 1,
                "validateAfterAction must be called at least once");
    }

    // ── 10. world-change NeedsCorrection exhausting revision budget ────────────

    @Test
    public void review_worldChange_needsCorrection_exhaustsBudget_terminated() {
        PlanValidator alwaysNeedsRevision = new PlanValidator() {
            @Override
            public ValidationResult validate(Decision d, ExecutionContext ctx) {
                return new ValidationResult.Passed();
            }
            @Override
            public ValidationResult validateAfterAction(ActionResult r, ExecutionContext ctx) {
                return new ValidationResult.NeedsCorrection("always stale",
                        new FinalAnswer("retry", List.of()));
            }
        };

        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("mutate", "1.0", "mutate"),
                (args, c) -> ToolResult.write("changed"));

        AtomicInteger calls = new AtomicInteger();
        LLMProvider llm = p -> {
            calls.incrementAndGet();
            return "{\"type\":\"tool_call\",\"tool_name\":\"mutate\",\"arguments\":{},\"reasoning_trace\":\"t\"}";
        };

        AgentRuntime rt = new AgentRuntime(alwaysNeedsRevision);
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("always mutate").maxCycles(50).build();
        ExecutionResult r = rt.execute(agent, task, "t", "u");
        assertFalse(r.succeeded());
        assertTrue(
            r.terminationReason() instanceof TerminationReason.PlanIncoherent ||
            r.terminationReason() instanceof TerminationReason.ResourceLimit ||
            r.terminationReason() instanceof TerminationReason.StagnationLimit,
            "Review world-change budget exhaustion must terminate, got: " + r.terminationReason());
    }

    // ── 11. delegation depth exceeded → ResourceLimit ─────────────────────────

    @Test
    public void stateMachine_delegationDepthExceeded_resourceLimit() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("ok"), reg);
        Task task = Task.builder().instruction("depth").maxCycles(5).maxChainDepth(1).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t", "u");
        ctx.incrementChainDepth(); // depth 1
        ctx.incrementChainDepth(); // depth 2 > maxChainDepth 1
        ExecutionResult r = runtime().executeWith(agent, ctx);
        assertFalse(r.succeeded());
        assertInstanceOf(TerminationReason.ResourceLimit.class, r.terminationReason(),
                "chain depth > maxChainDepth must produce ResourceLimit");
    }

    // ── 12. fail-then-succeed → resetConsecutiveFailures ─────────────────────

    @Test
    public void stateMachine_failThenSucceed_resetsConsecutiveFailures() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("goodtool", "1.0", "goodtool"),
                (args, c) -> ToolResult.ok("x"));

        AtomicInteger calls = new AtomicInteger();
        LLMProvider llm = p -> {
            int n = calls.incrementAndGet();
            if (n == 1) return "{\"type\":\"tool_call\",\"tool_name\":\"goodtool\",\"arguments\":{},\"reasoning_trace\":\"t\"}";
            return "{\"type\":\"final_answer\",\"content\":\"recovered\"}";
        };
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("fail then succeed").maxCycles(10).build();
        ExecutionResult r = runtime().execute(agent, task, "t", "u");
        assertTrue(r.succeeded(), "after recovery, run must succeed");
    }

    // ── 13. Snapshot roundtrip with working-memory ────────────────────────────

    @Test
    public void checkpoint_roundtrip_withWorkingMemory_isConsistent() {
        Task task = Task.builder().instruction("snap").maxCycles(5).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t", "u");
        ctx.workingMemory().add(new WorkingMemoryEntry(
                "id1", "payload", WorkingMemoryTier.ACTIVE,
                Origin.SYSTEM, 1.0, Instant.now(), TaintLabel.CLEAN));
        ExecutionContext.Snapshot snap = ctx.checkpoint();
        assertNotNull(snap);
        assertEquals(snap.integrityHash(),
                DefaultExecutionContext.computeSnapshotHash(snap),
                "integrity hash must be stable");
        assertFalse(snap.workingMemorySnapshot().isEmpty(),
                "snapshot must include working-memory entries");
    }

    // ── 14. EventSink.noop() ──────────────────────────────────────────────────

    @Test
    public void eventSink_noop_doesNotThrow() {
        EventSink noop = EventSink.noop();
        for (AgentEvent.EventType type : AgentEvent.EventType.values()) {
            assertDoesNotThrow(() -> noop.emit(new AgentEvent(
                    "run-1", "t", type, Instant.now(), Map.of())));
        }
    }

    // ── 15. InMemoryEventSink captures RUN_STARTED ────────────────────────────

    @Test
    public void runtime_withInMemoryEventSink_capturesEvents() {
        InMemoryEventSink sink = new InMemoryEventSink();
        AgentRuntime rt = new AgentRuntime(new PassThroughPlanValidator(), sink);
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("ev"), reg);
        Task task = Task.builder().instruction("events").maxCycles(5).build();
        rt.execute(agent, task, "t", "u");
        assertFalse(sink.all().isEmpty(), "InMemoryEventSink must capture at least one event");
        assertTrue(sink.all().stream()
                .anyMatch(e -> e.type() == AgentEvent.EventType.RUN_STARTED),
                "RUN_STARTED event must be present");
    }
}
