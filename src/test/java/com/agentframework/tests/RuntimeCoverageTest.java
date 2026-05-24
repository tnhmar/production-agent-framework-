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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Covers uncovered StateMachineRunner / AgentRuntime branches:
 *   - ValidationResult.NeedsCorrection + revision-budget exhaustion => PlanIncoherent
 *   - ValidationResult.Failed => immediate PlanIncoherent
 *   - Token / wall-clock / budget resource limits
 *   - Review world-change re-validation path
 *   - FailureEscalation termination
 *   - Snapshot hash with beliefs and working-memory entries
 */
public class RuntimeCoverageTest {

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

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

    // A reusable suggested-decision for NeedsCorrection
    private static final Decision RETRY_DECISION = new FinalAnswer("retry", List.of());

    // ─────────────────────────────────────────────────────────────────
    // NeedsCorrection => revision budget exhausted => PlanIncoherent
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void stateMachine_needsCorrectionExhaustsRevisionBudget_planIncoherent() {
        PlanValidator alwaysNeedsCorrection = new PlanValidator() {
            @Override
            public ValidationResult validate(Decision d, ExecutionContext ctx) {
                // NeedsCorrection(String reason, Decision suggestedDecision)
                return new ValidationResult.NeedsCorrection("always wrong", RETRY_DECISION);
            }
            @Override
            public ValidationResult validateAfterAction(ActionResult r, ExecutionContext ctx) {
                return new ValidationResult.Passed();
            }
        };

        SimpleToolRegistry reg = new SimpleToolRegistry();
        AgentRuntime rt = new AgentRuntime(alwaysNeedsCorrection);
        Agent agent = agentWith(StubLLMProvider.finalAnswer("done"), reg);
        Task task = Task.builder().instruction("test").maxCycles(20).build();

        ExecutionResult r = rt.execute(agent, task);
        assertFalse(r.succeeded());
        assertInstanceOf(TerminationReason.PlanIncoherent.class, r.terminationReason(),
                "revision budget exhaustion must produce PlanIncoherent, got: " + r.terminationReason());
    }

    // ─────────────────────────────────────────────────────────────────
    // ValidationResult.Failed => immediate PlanIncoherent
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void stateMachine_validationFailed_planIncoherent() {
        PlanValidator alwaysFails = new PlanValidator() {
            @Override
            public ValidationResult validate(Decision d, ExecutionContext ctx) {
                // Failed(String reason, List<String> details)
                return new ValidationResult.Failed("hard fail", List.of());
            }
            @Override
            public ValidationResult validateAfterAction(ActionResult r, ExecutionContext ctx) {
                return new ValidationResult.Passed();
            }
        };

        SimpleToolRegistry reg = new SimpleToolRegistry();
        AgentRuntime rt = new AgentRuntime(alwaysFails);
        Agent agent = agentWith(StubLLMProvider.finalAnswer("done"), reg);
        Task task = Task.builder().instruction("test").maxCycles(5).build();

        ExecutionResult r = rt.execute(agent, task);
        assertFalse(r.succeeded());
        assertInstanceOf(TerminationReason.PlanIncoherent.class, r.terminationReason(),
                "Failed validation must produce PlanIncoherent");
    }

    // ─────────────────────────────────────────────────────────────────
    // maxChainDepth=0 (runtime default=10) normal path succeeds
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void stateMachine_defaultChainDepth_succeeds() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("done"), reg);
        Task task = Task.builder().instruction("ok").maxCycles(5).maxChainDepth(0).build();
        ExecutionResult r = runtime().execute(agent, task, "t", "u");
        assertTrue(r.succeeded(), "depth=0 (default 10) must not block a normal run");
    }

    // ─────────────────────────────────────────────────────────────────
    // Token resource limit (maxTokens=1)
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void stateMachine_tokenLimitExceeded_resourceLimit() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        LLMProvider llm = prompt -> {
            calls.incrementAndGet();
            return "{\"type\":\"tool_call\",\"tool_name\":\"step\",\"arguments\":{},\"reasoning_trace\":\"step\"}";
        };
        reg.register(ToolContract.readOnly("step", "1.0", "step"),
                (args, ctx) -> ToolResult.ok("done"));
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("burn tokens").maxCycles(50).maxTokens(1).build();
        ExecutionResult r = runtime().execute(agent, task, "t", "u");
        assertFalse(r.succeeded());
        assertTrue(
                r.terminationReason() instanceof TerminationReason.ResourceLimit ||
                r.terminationReason() instanceof TerminationReason.StagnationLimit,
                "expected ResourceLimit or StagnationLimit, got: " + r.terminationReason());
    }

    // ─────────────────────────────────────────────────────────────────
    // Wall-clock resource limit (maxWallClockTime=1ns)
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void stateMachine_wallClockLimitExceeded_resourceLimit() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        LLMProvider llm = prompt -> {
            calls.incrementAndGet();
            return "{\"type\":\"tool_call\",\"tool_name\":\"step\",\"arguments\":{},\"reasoning_trace\":\"s\"}";
        };
        reg.register(ToolContract.readOnly("step", "1.0", "step"),
                (args, ctx) -> ToolResult.ok("ok"));
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("wall clock test").maxCycles(100)
                .maxWallClockTime(Duration.ofNanos(1))
                .build();
        ExecutionResult r = runtime().execute(agent, task, "t", "u");
        assertFalse(r.succeeded());
        assertTrue(
                r.terminationReason() instanceof TerminationReason.ResourceLimit ||
                r.terminationReason() instanceof TerminationReason.StagnationLimit,
                "expected ResourceLimit or StagnationLimit, got: " + r.terminationReason());
    }

    // ─────────────────────────────────────────────────────────────────
    // Budget cost resource limit (budgetLimit=ZERO)
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void stateMachine_budgetLimitExceeded_resourceLimit() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        LLMProvider llm = prompt -> {
            calls.incrementAndGet();
            return "{\"type\":\"tool_call\",\"tool_name\":\"step\",\"arguments\":{},\"reasoning_trace\":\"s\"}";
        };
        reg.register(ToolContract.readOnly("step", "1.0", "step"),
                (args, ctx) -> ToolResult.ok("ok"));
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("budget test").maxCycles(100)
                .budgetLimit(BigDecimal.ZERO)
                .build();
        ExecutionResult r = runtime().execute(agent, task, "t", "u");
        assertFalse(r.succeeded());
        assertTrue(
                r.terminationReason() instanceof TerminationReason.ResourceLimit ||
                r.terminationReason() instanceof TerminationReason.StagnationLimit,
                "expected ResourceLimit or StagnationLimit, got: " + r.terminationReason());
    }

    // ─────────────────────────────────────────────────────────────────
    // Review world-change path: validateAfterAction is invoked
    // ToolResult.write() sets WRITE_NON_IDEMPOTENT => indicatesWorldChange() == true
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void review_worldChangeRevalidation_validateAfterActionInvoked() {
        AtomicInteger validateAfterActionCalls = new AtomicInteger();
        PlanValidator revalidator = new PlanValidator() {
            @Override
            public ValidationResult validate(Decision d, ExecutionContext ctx) {
                return new ValidationResult.Passed();
            }
            @Override
            public ValidationResult validateAfterAction(ActionResult r, ExecutionContext ctx) {
                validateAfterActionCalls.incrementAndGet();
                return new ValidationResult.Passed();
            }
        };

        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("change", "1.0", "write tool"),
                (args, ctx) -> ToolResult.write("state changed"));

        AtomicInteger calls = new AtomicInteger();
        LLMProvider llm = prompt -> {
            int c = calls.incrementAndGet();
            return c == 1
                    ? "{\"type\":\"tool_call\",\"tool_name\":\"change\",\"arguments\":{},\"reasoning_trace\":\"t\"}"
                    : "{\"type\":\"final_answer\",\"content\":\"done\"}";
        };

        AgentRuntime rt = new AgentRuntime(revalidator);
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("change world").maxCycles(20).build();
        rt.execute(agent, task, "t", "u");

        assertTrue(validateAfterActionCalls.get() >= 1,
                "validateAfterAction must be called at least once for a world-change result");
    }

    // ─────────────────────────────────────────────────────────────────
    // 3x consecutive ToolException => FailureEscalation
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void runtime_failureEscalation_threeConsecutiveToolExceptions() {
        InMemoryEventSink sink = new InMemoryEventSink();
        AgentRuntime rt = new AgentRuntime(new PassThroughPlanValidator(), sink);
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("fail", "1.0", "fail"),
                (args, ctx) -> { throw new ToolException("ERR", "forced"); });
        LLMProvider llm = p ->
                "{\"type\":\"tool_call\",\"tool_name\":\"fail\",\"arguments\":{},\"reasoning_trace\":\"t\"}";
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("force failure").maxCycles(20).build();
        ExecutionResult r = rt.execute(agent, task, "t", "u");
        assertFalse(r.succeeded());
        assertInstanceOf(TerminationReason.FailureEscalation.class, r.terminationReason(),
                "3 consecutive ToolExceptions must produce FailureEscalation");
    }

    // ─────────────────────────────────────────────────────────────────
    // Snapshot hash is stable with populated beliefs and working memory
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void snapshotHash_withBeliefAndWorkingMemory_isConsistent() {
        Task task = Task.builder().instruction("hash test").build();
        DefaultExecutionContext c = new DefaultExecutionContext(task, "t", "u");
        c.beliefState().assertBelief(
                new Belief("b1", "sky", "color", "blue", 0.9, "obs", Instant.now(), false));
        c.workingMemory().add(new WorkingMemoryEntry(
                "w1", "hello world", WorkingMemoryTier.ACTIVE,
                Origin.TOOL, 0.8, Instant.now(), TaintLabel.CLEAN));
        ExecutionContext.Snapshot snap = c.checkpoint();
        assertEquals(snap.integrityHash(),
                DefaultExecutionContext.computeSnapshotHash(snap),
                "snapshot hash must be stable across checkpoint and static helper");
    }
}
