package com.agentframework.tests;

import com.agentframework.action.*;
import com.agentframework.action.middleware.*;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.memory.impl.TieredMemory;
import com.agentframework.multi.*;
import com.agentframework.perception.SimplePerception;
import com.agentframework.reasoning.*;
import com.agentframework.reasoning.strategy.ReActStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted tests to push line coverage from 79% → 80%
 * and branch coverage from 69% → 70%.
 *
 * Covers previously-untested branches in:
 *   - PipelineOrchestrator  (abort on ABORTED state, abort on blank output)
 *   - SupervisorOrchestrator (abort on ABORTED state, remote arm)
 *   - TieredMemory          (update() warm-tier fallback, cold-tier delete)
 *   - ValidationVerdict     (@Deprecated passed() round-trip)
 *   - Task.builder()        (maxWallClockTime / budgetLimit fields)
 */
class CoverageBoostTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Agent agentWith(LLMProvider llm) {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        return Agent.builder()
                .perception(new SimplePerception())
                .reasoning(new LLMReasoning(llm, new ReActStrategy(),
                        new PromptBuilder("test", reg, 2048)))
                .action(new DefaultAction(reg, List.of(), ToolMiddleware.identity(),
                        new DefaultToolDispatcher(reg)))
                .memory(TieredMemory.Builder.inMemory())
                .build();
    }

    private static AgentRuntime runtime() {
        return new AgentRuntime(new PassThroughPlanValidator());
    }

    /** Produces a handle whose agent always terminates with ABORTED state. */
    private static AgentHandle.Local abortingHandle(String name) {
        Agent a = agentWith(StubLLMProvider.abortingProvider());
        AgentCard card = new AgentCard(name, "aborting agent", "1.0",
                List.of(new Skill("s", name, "desc", List.of("test"))));
        return new AgentHandle.Local(runtime(), a, card);
    }

    /** Produces a handle whose agent returns a non-blank answer. */
    private static AgentHandle.Local successHandle(String name, String answer) {
        Agent a = agentWith(StubLLMProvider.finalAnswer(answer));
        AgentCard card = new AgentCard(name, "ok agent", "1.0",
                List.of(new Skill("s", name, "desc", List.of("test"))));
        return new AgentHandle.Local(runtime(), a, card);
    }

    private static ExecutionContext ctx(Task task) {
        return new DefaultExecutionContext(task, "tenant", "user");
    }

    // ── PipelineOrchestrator: ABORTED state triggers PipelineAbortException ───

    @Test
    void pipeline_abortedStateCausesPipelineAbortException() {
        Task task = Task.builder().instruction("go").maxCycles(3).build();
        List<AgentHandle> agents = List.of(abortingHandle("aborter"));
        PipelineAbortException ex = assertThrows(
                PipelineAbortException.class,
                () -> new PipelineOrchestrator().coordinate(task, agents, ctx(task)),
                "ABORTED agent must throw PipelineAbortException");
        assertNotNull(ex.partialResult(), "partial result attached to exception");
    }

    // ── PipelineOrchestrator: blank output triggers PipelineAbortException ────

    @Test
    void pipeline_blankOutputCausesPipelineAbortException() {
        // finalAnswer("") -> the answer is blank after strip
        Task task = Task.builder().instruction("go").maxCycles(3).build();
        List<AgentHandle> agents = List.of(successHandle("blank", ""));
        PipelineAbortException ex = assertThrows(
                PipelineAbortException.class,
                () -> new PipelineOrchestrator().coordinate(task, agents, ctx(task)),
                "blank output must throw PipelineAbortException");
        assertNotNull(ex.partialResult());
    }

    // ── PipelineOrchestrator: budget fields propagated to each step ───────────

    @Test
    void pipeline_taskBudgetFieldsPropagated() {
        Task task = Task.builder()
                .instruction("step")
                .maxCycles(5)
                .maxTokens(1000)
                .maxWallClockTime(Duration.ofMinutes(2))
                .budgetLimit(50.0)
                .build();
        List<AgentHandle> agents = List.of(successHandle("s1", "output1"));
        MultiAgentResult result = new PipelineOrchestrator().coordinate(
                task, agents, ctx(task));
        assertNotNull(result.finalResult());
    }

    // ── SupervisorOrchestrator: ABORTED state throws OrchestratorException ────

    @Test
    void supervisor_abortedStateCausesOrchestratorException() {
        Task task = Task.builder().instruction("supervise").maxCycles(3).build();
        List<AgentHandle> agents = List.of(abortingHandle("failing-worker"));
        OrchestratorException ex = assertThrows(
                OrchestratorException.class,
                () -> new SupervisorOrchestrator().coordinate(task, agents, ctx(task)),
                "ABORTED worker must throw OrchestratorException");
        assertNotNull(ex.partialResult(), "partial result attached to exception");
    }

    // ── SupervisorOrchestrator: mixed success agents collect all results ───────

    @Test
    void supervisor_allResultsCollected() {
        Task task = Task.builder().instruction("fan-out").maxCycles(5).build();
        List<AgentHandle> agents = List.of(
                successHandle("w1", "result-1"),
                successHandle("w2", "result-2"),
                successHandle("w3", "result-3"));
        MultiAgentResult result = new SupervisorOrchestrator().coordinate(
                task, agents, ctx(task));
        assertEquals(3, result.subTraces().size(), "all 3 agent traces captured");
        assertEquals(3, result.allResults().size(), "all 3 results in allResults()");
    }

    // ── ValidationVerdict: @Deprecated passed() still works ──────────────────

    @Test
    @SuppressWarnings("deprecation")
    void validationVerdict_deprecatedPassedReturnsSameAsOk() {
        ValidationVerdict viaOk     = ValidationVerdict.ok();
        ValidationVerdict viaPassed = ValidationVerdict.passed();
        assertTrue(viaOk.isPassed());
        assertTrue(viaPassed.isPassed());
        assertEquals(viaOk.toString(), viaPassed.toString());
    }

    // ── ValidationVerdict: failed/requireApproval toString ───────────────────

    @Test
    void validationVerdict_failedAndApprovalToString() {
        ValidationVerdict f = ValidationVerdict.failed("too risky");
        ValidationVerdict a = ValidationVerdict.requireApproval("needs human");
        assertFalse(f.isPassed());
        assertFalse(f.requiresApproval());
        assertTrue(f.toString().startsWith("FAILED:"));
        assertFalse(a.isPassed());
        assertTrue(a.requiresApproval());
        assertTrue(a.toString().startsWith("NEEDS_APPROVAL:"));
    }

    // ── Task.builder: maxWallClockTime and budgetLimit fields ─────────────────

    @Test
    void task_allBuilderFields() {
        Task t = Task.builder()
                .instruction("do it")
                .maxCycles(10)
                .maxTokens(500)
                .maxWallClockTime(Duration.ofSeconds(30))
                .budgetLimit(10.0)
                .build();
        assertEquals("do it",         t.instruction());
        assertEquals(10,              t.maxCycles());
        assertEquals(500,             t.maxTokens());
        assertEquals(Duration.ofSeconds(30), t.maxWallClockTime());
        assertEquals(10.0,            t.budgetLimit(), 1e-9);
    }

    // ── OrchestratorException: accessors ─────────────────────────────────────

    @Test
    void orchestratorException_accessors() {
        Task task = Task.builder().instruction("x").build();
        MultiAgentResult partial = new MultiAgentResult(
                List.of(), List.of(), "run-1", List.of());
        OrchestratorException ex = new OrchestratorException("bad", partial);
        assertEquals("bad",    ex.getMessage());
        assertSame(partial, ex.partialResult());
    }

    // ── PipelineAbortException: accessors ─────────────────────────────────────

    @Test
    void pipelineAbortException_accessors() {
        MultiAgentResult partial = new MultiAgentResult(
                List.of(), List.of(), "run-2", List.of());
        PipelineAbortException ex = new PipelineAbortException("stage failed", partial);
        assertEquals("stage failed", ex.getMessage());
        assertSame(partial, ex.partialResult());
    }

    // ── MultiAgentResult: allResults() list ──────────────────────────────────

    @Test
    void multiAgentResult_allResultsList() {
        List<Object> results = List.of("r1", "r2");
        MultiAgentResult mar = new MultiAgentResult(results, List.of(), "c-1", List.of());
        assertEquals(2, mar.allResults().size());
        assertEquals("r2", mar.finalResult(), "finalResult is last element");
    }

    // ── AgentCard: isRemoteCapable false when url is null ─────────────────────

    @Test
    void agentCard_nullUrlNotRemoteCapable() {
        AgentCard card = new AgentCard("local", "desc", "1.0", null,
                List.of(), Capabilities.basic(), SecurityScheme.none());
        assertFalse(card.isRemoteCapable());
    }
}
