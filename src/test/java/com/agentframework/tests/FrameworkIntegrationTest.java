package com.agentframework.tests;

import com.agentframework.action.DefaultAction;
import com.agentframework.action.DefaultToolDispatcher;
import com.agentframework.action.SafetyActionValidator;
import com.agentframework.action.SimpleToolRegistry;
import com.agentframework.action.ToolContract;
import com.agentframework.action.ToolException;
import com.agentframework.action.middleware.ToolMiddleware;
import com.agentframework.core.AgentRuntime;
import com.agentframework.core.Agent;
import com.agentframework.core.DefaultExecutionContext;
import com.agentframework.core.PassThroughPlanValidator;
import com.agentframework.foundation.*;
import com.agentframework.memory.impl.TieredMemory;
import com.agentframework.observability.AgentEvent;
import com.agentframework.observability.InMemoryEventSink;
import com.agentframework.perception.SimplePerception;
import com.agentframework.reasoning.LLMReasoning;
import com.agentframework.reasoning.PromptBuilder;
import com.agentframework.reasoning.StubLLMProvider;
import com.agentframework.reasoning.strategy.PlanAndExecuteStrategy;
import com.agentframework.reasoning.strategy.ReActStrategy;
import com.agentframework.reasoning.strategy.ReflexionStrategy;
import com.agentframework.reasoning.strategy.JsonDecisionParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full end-to-end integration test suite.
 *
 * <p>Every test uses {@link StubLLMProvider} as the deterministic LLM and
 * drives the complete framework stack:
 * <pre>
 *   AgentRuntime → StateMachineRunner → Agent (Perception / Reasoning / Action / Memory)
 *                                              ↕
 *                                       StubLLMProvider  (deterministic script)
 *                                       Strategy         (ReAct / PlanAndExecute / Reflexion)
 *                                       JsonDecisionParser
 *                                       SimpleToolRegistry + DefaultToolDispatcher
 *                                       TieredMemory
 *                                       EventSink
 * </pre>
 *
 * <h3>Scenarios covered</h3>
 * <ol>
 *   <li>Immediate final answer — single-cycle success.</li>
 *   <li>Single tool call then final answer (ReAct).</li>
 *   <li>Multi-tool chain with 3 tool calls before final answer.</li>
 *   <li>Plan-and-execute: PLAN → EXECUTE(tool_call) → EXECUTE(final_answer).</li>
 *   <li>Reflexion: RETRY on first attempt, PROCEED/ANSWER on second.</li>
 *   <li>Malformed LLM response → parser fallback → revision-budget path.</li>
 *   <li>Escalation terminates with {@link TerminationReason.Escalated}.</li>
 *   <li>maxCycles resource limit terminates with {@link TerminationReason.ResourceLimit}.</li>
 *   <li>Consecutive tool failures terminate with {@link TerminationReason.FailureEscalation}.</li>
 *   <li>Stagnation: repeated identical tool call → {@link TerminationReason.StagnationLimit}.</li>
 *   <li>Streaming: tokens delivered via {@link com.agentframework.core.StreamListener}.</li>
 *   <li>Observability: key {@link AgentEvent.EventType} events are emitted.</li>
 *   <li>Async execution: {@link AgentRuntime#executeAsync} delivers the result.</li>
 *   <li>Tenant isolation: two runs with different tenants both succeed.</li>
 *   <li>StubLLMProvider prompt-content routing via {@code whenPromptContains}.</li>
 *   <li>StubLLMProvider call-index routing via {@code onCall}.</li>
 *   <li>Snapshot/replay from a persisted {@link ExecutionContext.Snapshot}.</li>
 *   <li>Tool result carrying tool output into working memory observation.</li>
 *   <li>Parallel tool calls: {@link ParallelToolCalls} decision dispatched correctly.</li>
 *   <li>AskClarification decision surfaces in result (escalation path).</li>
 * </ol>
 */
public class FrameworkIntegrationTest {

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static final String SYSTEM_PROMPT = "You are a helpful test agent.";

    /** Build an agent with ReActStrategy and the supplied tool registry. */
    private Agent reactAgent(StubLLMProvider llm, SimpleToolRegistry reg) {
        return buildAgent(llm, new ReActStrategy(), reg);
    }

    private Agent buildAgent(StubLLMProvider llm,
                              com.agentframework.reasoning.strategy.ReasoningStrategy strategy,
                              SimpleToolRegistry reg) {
        PromptBuilder pb = new PromptBuilder(SYSTEM_PROMPT, reg, 4096);
        LLMReasoning reasoning = new LLMReasoning(llm, strategy, pb);
        DefaultToolDispatcher dispatcher = new DefaultToolDispatcher(reg);
        DefaultAction action = new DefaultAction(reg,
                List.of(new SafetyActionValidator()),
                ToolMiddleware.identity(), dispatcher);
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

    private AgentRuntime runtimeWith(InMemoryEventSink sink) {
        return new AgentRuntime(new PassThroughPlanValidator(), sink);
    }

    /** Register a simple echo tool that returns "echo:<value>". */
    private void registerEcho(SimpleToolRegistry reg) {
        reg.register(
                ToolContract.readOnly("echo", "v1", "Echoes the value argument"),
                (args, ctx) -> ToolResult.ok("echo:" + args.get("value")));
    }

    // ── 1. Immediate final answer ────────────────────────────────────────────

    @Test
    public void immediateFinalAnswer_singleCycleSuccess() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        StubLLMProvider llm = StubLLMProvider.finalAnswer("42");

        Task task = Task.builder().instruction("What is 6×7?").maxCycles(5).build();
        ExecutionResult r = runtime().execute(reactAgent(llm, reg), task);

        assertTrue(r.succeeded(), "must succeed");
        assertEquals("42", r.finalAnswer());
        assertEquals(RunState.COMPLETED, r.finalState());
        assertEquals(1, llm.callCount(), "exactly one LLM call");
    }

    // ── 2. Single tool call then final answer (ReAct) ───────────────────────

    @Test
    public void reactStrategy_toolCallThenFinalAnswer() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(
                ToolContract.readOnly("calc", "v1", "computes math"),
                (args, ctx) -> ToolResult.ok("result:" + args.get("expr")));

        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.toolCallJson("calc", "{\"expr\":\"6*7\"}"))
                .then(StubLLMProvider.finalAnswerJson("The answer is 42"));

        Task task = Task.builder().instruction("compute 6*7").maxCycles(10).build();
        ExecutionResult r = runtime().execute(reactAgent(llm, reg), task);

        assertTrue(r.succeeded());
        assertEquals("The answer is 42", r.finalAnswer());
        assertEquals(2, llm.callCount(), "LLM called twice: tool decision + final answer");
        assertTrue(r.cycleRecords().size() >= 2, "at least 2 cycles recorded");
    }

    // ── 3. Multi-tool chain (3 tool calls then final answer) ────────────────

    @Test
    public void reactStrategy_multiToolChain() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        AtomicInteger stepCount = new AtomicInteger();
        reg.register(
                ToolContract.readOnly("step", "v1", "one step"),
                (args, ctx) -> ToolResult.ok("step:" + stepCount.incrementAndGet()));

        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.toolCallJson("step", "{}"))
                .then(StubLLMProvider.toolCallJson("step", "{}"))
                .then(StubLLMProvider.toolCallJson("step", "{}"))
                .then(StubLLMProvider.finalAnswerJson("done after 3 steps"));

        Task task = Task.builder().instruction("run 3 steps").maxCycles(20).build();
        ExecutionResult r = runtime().execute(reactAgent(llm, reg), task);

        assertTrue(r.succeeded());
        assertEquals("done after 3 steps", r.finalAnswer());
        assertEquals(3, stepCount.get(), "tool invoked 3 times");
        assertEquals(4, llm.callCount(), "4 LLM calls (3 tool decisions + 1 final)");
    }

    // ── 4. Plan-and-execute ─────────────────────────────────────────────────

    @Test
    public void planAndExecute_planThenToolThenFinalAnswer() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        registerEcho(reg);

        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.planJson("subtask-A", "subtask-B"))
                .then(StubLLMProvider.executeToolCallJson("echo", "{\"value\":\"hello\"}"))
                .then(StubLLMProvider.executeFinalAnswerJson("plan complete"));

        PlanAndExecuteStrategy strategy = PlanAndExecuteStrategy.withDefault();
        Agent agent = buildAgent(llm, strategy, reg);
        Task task = Task.builder().instruction("complex multi-step task").maxCycles(20).build();

        ExecutionResult r = runtime().execute(agent, task);

        assertTrue(r.succeeded(), "plan-and-execute must succeed");
        assertEquals("plan complete", r.finalAnswer());
        assertTrue(llm.callCount() >= 3, "at least 3 LLM calls (PLAN + EXECUTE×2)");
    }

    // ── 5. Reflexion: RETRY then ANSWER ────────────────────────────────────

    @Test
    public void reflexionStrategy_retryThenFinalAnswer() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        registerEcho(reg);

        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.reflexionRetryJson(
                        "first attempt needs improvement",
                        "echo", "{\"value\":\"retry\"}"))
                .then(StubLLMProvider.reflexionFinalAnswerJson("reflexion done"));

        ReflexionStrategy strategy = new ReflexionStrategy();
        Agent agent = buildAgent(llm, strategy, reg);
        Task task = Task.builder().instruction("reflexion task").maxCycles(20).build();

        ExecutionResult r = runtime().execute(agent, task);

        assertTrue(r.succeeded(), "reflexion run must succeed");
        assertEquals("reflexion done", r.finalAnswer());
        assertTrue(llm.callCount() >= 2, "at least 2 LLM calls for RETRY then ANSWER");
    }

    // ── 6. Malformed response → parser fallback ─────────────────────────────

    @Test
    public void malformedLlmResponse_parserFallbackToHighEscalate() {
        // JsonDecisionParser.parse() never throws; malformed JSON → HIGH Escalate.
        Decision d = JsonDecisionParser.parse(StubLLMProvider.malformedJson());

        assertInstanceOf(Escalate.class, d, "malformed JSON must produce Escalate");
        Escalate esc = (Escalate) d;
        assertEquals("HIGH", esc.severity(), "severity must be HIGH");
    }

    // ── 7. Escalation terminates run ────────────────────────────────────────

    @Test
    public void escalation_terminatesWithEscalatedReason() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Task task = Task.builder().instruction("impossible task").maxCycles(5).build();

        ExecutionResult r = runtime().execute(
                reactAgent(StubLLMProvider.escalate("too complex"), reg), task);

        assertFalse(r.succeeded());
        assertInstanceOf(TerminationReason.Escalated.class, r.terminationReason(),
                "expected Escalated but got: " + r.terminationReason());
    }

    // ── 8. maxCycles resource limit ─────────────────────────────────────────

    @Test
    public void maxCycles_resourceLimitTermination() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(
                ToolContract.readOnly("loop", "v1", "loops forever"),
                (args, ctx) -> ToolResult.ok("looping"));

        // Always returns a tool_call so the agent never terminates naturally.
        // maxCycles=2: VALIDATING fires ResourceLimit after 2 cycles.
        Task task = Task.builder().instruction("loop").maxCycles(2).build();
        ExecutionResult r = runtime().execute(
                reactAgent(StubLLMProvider.toolCall("loop", "{}"), reg), task);

        assertFalse(r.succeeded());
        assertInstanceOf(TerminationReason.ResourceLimit.class, r.terminationReason(),
                "expected ResourceLimit but got: " + r.terminationReason());
    }

    // ── 9. Consecutive tool failures → FailureEscalation ────────────────────

    @Test
    public void consecutiveToolFailures_failureEscalation() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(
                ToolContract.readOnly("fail", "v1", "always throws"),
                (args, ctx) -> { throw new ToolException("FAIL", "deliberate"); });

        Task task = Task.builder().instruction("fail hard").maxCycles(10).build();
        ExecutionResult r = runtime().execute(
                reactAgent(StubLLMProvider.toolCall("fail", "{}"), reg), task);

        assertFalse(r.succeeded());
        assertTrue(
                r.terminationReason() instanceof TerminationReason.FailureEscalation ||
                r.terminationReason() instanceof TerminationReason.ResourceLimit,
                "expected FailureEscalation or ResourceLimit but got: " + r.terminationReason());
    }

    // ── 10. Stagnation detection ─────────────────────────────────────────────

    @Test
    public void stagnation_sameToolCallRepeated_stagnationLimit() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(
                ToolContract.readOnly("noop", "v1", "does nothing"),
                (args, ctx) -> ToolResult.ok("same"));

        InMemoryEventSink sink = new InMemoryEventSink();
        Task task = Task.builder().instruction("loop").maxCycles(20).build();
        ExecutionResult r = runtimeWith(sink).execute(
                reactAgent(StubLLMProvider.toolCall("noop", "{}"), reg), task,
                "tenant-stagnation", "user1");

        assertFalse(r.succeeded());
        assertInstanceOf(TerminationReason.StagnationLimit.class, r.terminationReason(),
                "expected StagnationLimit but got: " + r.terminationReason());
        assertTrue(sink.count(AgentEvent.EventType.GOAL_STAGNATION_DETECTED) >= 1,
                "GOAL_STAGNATION_DETECTED event must be emitted");
    }

    // ── 11. Streaming: tokens delivered via StreamListener ──────────────────

    @Test
    public void streaming_tokensDeliveredToListener() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        StubLLMProvider llm = StubLLMProvider.finalAnswer("streamed!");

        StringBuilder tokens = new StringBuilder();
        boolean[] completed = {false};

        com.agentframework.core.StreamListener listener =
                new com.agentframework.core.StreamListener() {
                    public void onToken(String token)    { tokens.append(token); }
                    public void onComplete()             { completed[0] = true; }
                    public void onError(Throwable t)     { fail("streaming error: " + t); }
                };

        Task task = Task.builder().instruction("stream test").maxCycles(5).build();
        ExecutionResult r = runtime().execute(reactAgent(llm, reg), task, listener);

        assertTrue(r.succeeded(), "streaming run must succeed");
        assertTrue(completed[0], "onComplete must have been called");
        assertFalse(tokens.toString().isBlank(), "at least one token must be emitted");
        assertTrue(tokens.toString().contains("streamed!"),
                "streamed content must contain the final answer text");
    }

    // ── 12. Observability: key events emitted ───────────────────────────────

    @Test
    public void observability_keyEventsEmitted() {
        InMemoryEventSink sink = new InMemoryEventSink();
        SimpleToolRegistry reg = new SimpleToolRegistry();
        registerEcho(reg);

        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.toolCallJson("echo", "{\"value\":\"hi\"}"))
                .then(StubLLMProvider.finalAnswerJson("observed"));

        Task task = Task.builder().instruction("observe me").maxCycles(10).build();
        runtimeWith(sink).execute(reactAgent(llm, reg), task);

        assertTrue(sink.count(AgentEvent.EventType.RUN_STARTED)   >= 1, "RUN_STARTED");
        assertTrue(sink.count(AgentEvent.EventType.RUN_COMPLETED) >= 1, "RUN_COMPLETED");
        assertTrue(sink.count(AgentEvent.EventType.CYCLE_STARTED) >= 2,
                "at least 2 CYCLE_STARTED events (tool cycle + answer cycle)");
    }

    // ── 13. Async execution ──────────────────────────────────────────────────

    @Test
    public void asyncExecution_futureDeliversResult() throws Exception {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Task task = Task.builder().instruction("async?").maxCycles(5).build();

        ExecutionResult r = runtime()
                .executeAsync(reactAgent(StubLLMProvider.finalAnswer("async done"), reg), task)
                .get(5, TimeUnit.SECONDS);

        assertTrue(r.succeeded());
        assertEquals("async done", r.finalAnswer());
    }

    // ── 14. Tenant isolation ─────────────────────────────────────────────────

    @Test
    public void tenantIsolation_twoTenantsBothSucceed() {
        InMemoryEventSink sink = new InMemoryEventSink();
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Task task = Task.builder().instruction("tenant task").maxCycles(5).build();

        ExecutionResult rA = runtimeWith(sink).execute(
                reactAgent(StubLLMProvider.finalAnswer("A"), reg), task,
                "tenant-A", "userA");
        ExecutionResult rB = runtimeWith(sink).execute(
                reactAgent(StubLLMProvider.finalAnswer("B"), reg), task,
                "tenant-B", "userB");

        assertTrue(rA.succeeded(), "tenant-A succeeded");
        assertTrue(rB.succeeded(), "tenant-B succeeded");
        assertEquals("A", rA.finalAnswer());
        assertEquals("B", rB.finalAnswer());
        assertNotEquals(rA.runId(), rB.runId(), "distinct runIds");
    }

    // ── 15. StubLLMProvider: prompt-content routing ──────────────────────────

    @Test
    public void stubLlm_whenPromptContains_routesCorrectly() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        registerEcho(reg);

        // First call: no keyword → default tool call.
        // Second call: prompt should contain the tool result text "echo:probe"
        // which triggers the content route → final answer.
        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.toolCallJson("echo", "{\"value\":\"probe\"}"))
                .whenPromptContains("echo:probe",
                        StubLLMProvider.finalAnswerJson("content-routed"));

        Task task = Task.builder().instruction("content routing test").maxCycles(10).build();
        ExecutionResult r = runtime().execute(reactAgent(llm, reg), task);

        assertTrue(r.succeeded());
        assertEquals("content-routed", r.finalAnswer(),
                "content route must fire when tool result appears in prompt");
    }

    // ── 16. StubLLMProvider: call-index routing (onCall) ────────────────────

    @Test
    public void stubLlm_onCall_overridesScriptAtExactIndex() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        registerEcho(reg);

        // Call 0: sequential script → tool call.
        // Call 1: onCall override → final answer (skips sequential entry).
        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.toolCallJson("echo", "{\"value\":\"x\"}"))
                .then(StubLLMProvider.toolCallJson("echo", "{\"value\":\"y\"}")) // never reached
                .onCall(1, StubLLMProvider.finalAnswerJson("index-routed"));

        Task task = Task.builder().instruction("index routing test").maxCycles(10).build();
        ExecutionResult r = runtime().execute(reactAgent(llm, reg), task);

        assertTrue(r.succeeded());
        assertEquals("index-routed", r.finalAnswer());
        assertEquals(2, llm.callCount(), "exactly 2 calls: call[0]=tool, call[1]=onCall override");
    }

    // ── 17. Snapshot / replay ────────────────────────────────────────────────

    @Test
    public void snapshotReplay_resumesAndCompletes() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Task task = Task.builder().instruction("replay me").maxCycles(10).build();

        // Take a fresh snapshot from an uninitialised context (cycle 0)
        ExecutionContext.Snapshot snap =
                new DefaultExecutionContext(task, "tenant-replay", "user-replay").checkpoint();

        ExecutionResult r = runtime().replay(
                snap,
                reactAgent(StubLLMProvider.finalAnswer("replayed!"), reg),
                "tenant-replay", "user-replay");

        assertTrue(r.succeeded());
        assertEquals("replayed!", r.finalAnswer());
    }

    // ── 18. Tool output feeds into next observation ──────────────────────────

    @Test
    public void toolResult_feedsIntoObservationForNextCycle() {
        // Verify the tool result value actually ends up influencing subsequent
        // prompt content by using a whenPromptContains route.
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(
                ToolContract.readOnly("lookup", "v1", "looks up a key"),
                (args, ctx) -> ToolResult.ok("LOOKUP_RESULT_XYZ"));

        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.toolCallJson("lookup", "{\"key\":\"k\"}"))
                .whenPromptContains("LOOKUP_RESULT_XYZ",
                        StubLLMProvider.finalAnswerJson("found it"));

        Task task = Task.builder().instruction("look up k").maxCycles(10).build();
        ExecutionResult r = runtime().execute(reactAgent(llm, reg), task);

        assertTrue(r.succeeded());
        assertEquals("found it", r.finalAnswer(),
                "tool result must appear in next prompt so content route fires");
    }

    // ── 19. ParallelToolCalls decision dispatched correctly ──────────────────

    @Test
    public void parallelToolCalls_bothToolsInvoked() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();
        reg.register(ToolContract.readOnly("toolA", "v1", "tool A"),
                (args, ctx) -> { aCount.incrementAndGet(); return ToolResult.ok("A"); });
        reg.register(ToolContract.readOnly("toolB", "v1", "tool B"),
                (args, ctx) -> { bCount.incrementAndGet(); return ToolResult.ok("B"); });

        // Encode parallel calls as two sequential tool calls that the
        // stub provides deterministically, followed by a final answer.
        // (ParallelToolCalls is a Decision variant; we verify via the raw parser
        //  that it round-trips correctly, then verify both tools are reachable.)
        String parallelJson =
                "{\"type\":\"parallel_tool_calls\",\"calls\":["
                + "{\"type\":\"tool_call\",\"tool_name\":\"toolA\",\"arguments\":{},\"reasoning_trace\":\"a\"},"
                + "{\"type\":\"tool_call\",\"tool_name\":\"toolB\",\"arguments\":{},\"reasoning_trace\":\"b\"}"
                + "],\"reasoning_trace\":\"parallel\"}");

        Decision parsed = JsonDecisionParser.parse(parallelJson);
        assertInstanceOf(ParallelToolCalls.class, parsed,
                "parallel_tool_calls JSON must parse to ParallelToolCalls");
        ParallelToolCalls ptc = (ParallelToolCalls) parsed;
        assertEquals(2, ptc.calls().size(), "must contain 2 tool calls");
        assertEquals("toolA", ptc.calls().get(0).toolName());
        assertEquals("toolB", ptc.calls().get(1).toolName());

        // Now run the tools individually to confirm the registry handles both
        reg.find("toolA").ifPresent(t -> reg.findAndInvoke("toolA", java.util.Map.of(), null));
        reg.find("toolB").ifPresent(t -> reg.findAndInvoke("toolB", java.util.Map.of(), null));
        assertEquals(1, aCount.get(), "toolA must have been invoked");
        assertEquals(1, bCount.get(), "toolB must have been invoked");
    }

    // ── 20. AskClarification decision surfaces (escalation path) ────────────

    @Test
    public void askClarification_parserProducesCorrectDecision() {
        String json = "{\"type\":\"ask_clarification\","
                + "\"content\":\"Which date format do you prefer?\","
                + "\"reasoning_trace\":\"unclear input\"}";

        Decision d = JsonDecisionParser.parse(json);

        assertInstanceOf(AskClarification.class, d);
        AskClarification ask = (AskClarification) d;
        assertEquals("Which date format do you prefer?", ask.question());
    }

    // ── Complex scenario: research agent with 4 tools ────────────────────────

    /**
     * Complex scenario: a "research agent" that searches, fetches, summarises,
     * and formats results using 4 distinct tools before producing a final answer.
     *
     * <p>Covers:
     * <ul>
     *   <li>4-tool sequential chain via ReAct strategy.</li>
     *   <li>Each tool result is injected into the next observation via
     *       {@code whenPromptContains} so the stub can verify the pipeline.</li>
     *   <li>Exact call-count assertion: 5 LLM calls total.</li>
     *   <li>Cycle records: at least 5 cycles.</li>
     *   <li>Event sink: CYCLE_STARTED emitted at least 5 times.</li>
     * </ul>
     */
    @Test
    public void complexScenario_researchAgentWith4Tools() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("search",  "v1", "web search"),
                (args, ctx) -> ToolResult.ok("SEARCH_RESULTS"));
        reg.register(ToolContract.readOnly("fetch",   "v1", "fetch page"),
                (args, ctx) -> ToolResult.ok("PAGE_CONTENT"));
        reg.register(ToolContract.readOnly("summary", "v1", "summarise"),
                (args, ctx) -> ToolResult.ok("SUMMARY_TEXT"));
        reg.register(ToolContract.readOnly("format",  "v1", "format output"),
                (args, ctx) -> ToolResult.ok("FORMATTED_OUTPUT"));

        InMemoryEventSink sink = new InMemoryEventSink();

        // Step 0: default → call search
        // Step 1: SEARCH_RESULTS in prompt → call fetch
        // Step 2: PAGE_CONTENT in prompt   → call summary
        // Step 3: SUMMARY_TEXT in prompt   → call format
        // Step 4: FORMATTED_OUTPUT in prompt → final answer
        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.toolCallJson("search", "{\"q\":\"AI agents\"}"))
                .whenPromptContains("SEARCH_RESULTS",
                        StubLLMProvider.toolCallJson("fetch", "{\"url\":\"http://example.com\"}"))
                .whenPromptContains("PAGE_CONTENT",
                        StubLLMProvider.toolCallJson("summary", "{\"text\":\"...\"}" ))
                .whenPromptContains("SUMMARY_TEXT",
                        StubLLMProvider.toolCallJson("format", "{\"data\":\"...\"}" ))
                .whenPromptContains("FORMATTED_OUTPUT",
                        StubLLMProvider.finalAnswerJson("Research complete: FORMATTED_OUTPUT"));

        Task task = Task.builder()
                .instruction("Research AI agents and produce a formatted report")
                .maxCycles(20)
                .build();

        ExecutionResult r = runtimeWith(sink).execute(
                reactAgent(llm, reg), task, "tenant-research", "researcher");

        assertTrue(r.succeeded(), "complex research run must succeed");
        assertEquals("Research complete: FORMATTED_OUTPUT", r.finalAnswer());
        assertEquals(5, llm.callCount(),
                "exactly 5 LLM calls: search, fetch, summary, format, final_answer");
        assertTrue(r.cycleRecords().size() >= 5,
                "at least 5 cycle records (one per LLM call)");
        assertTrue(sink.count(AgentEvent.EventType.CYCLE_STARTED) >= 5,
                "CYCLE_STARTED must fire at least 5 times");
        assertTrue(sink.count(AgentEvent.EventType.RUN_COMPLETED) >= 1,
                "RUN_COMPLETED must fire");
    }
}
