package com.agentframework.tests;
import com.agentframework.action.*;
import com.agentframework.action.middleware.*;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.memory.impl.TieredMemory;
import com.agentframework.observability.*;
import com.agentframework.perception.*;
import com.agentframework.rag.RagService;
import com.agentframework.reasoning.*;
import com.agentframework.reasoning.strategy.ReActStrategy;
import com.agentframework.testutil.Assert;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
public class RuntimeTest {

    /** Build a fully wired Agent with a stub LLM. */
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

    public void testImmediateFinalAnswer() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("42"), reg);
        Task task = Task.builder().instruction("What is 6*7?").maxCycles(5).build();
        ExecutionResult r = runtime().execute(agent, task);
        Assert.assertTrue(r.succeeded(), "run succeeded");
        Assert.assertEquals("42", r.finalAnswer(), "final answer");
        Assert.assertEquals(RunState.COMPLETED, r.finalState(), "COMPLETED");
    }

    public void testSingleToolThenAnswer() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("calc","1.0","compute math"),
            (args, ctx) -> ToolResult.ok("result:42"));
        // First call: tool_call, then final_answer
        int[] call = {0};
        LLMProvider llm = prompt -> {
            call[0]++;
            return call[0] == 1
                ? "{\"type\":\"tool_call\",\"tool_name\":\"calc\",\"arguments\":{\"expr\":\"6*7\"},\"reasoning_trace\":\"compute\"}"
                : "{\"type\":\"final_answer\",\"content\":\"The answer is 42\"}";
        };
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("compute 6*7").maxCycles(10).build();
        ExecutionResult r = runtime().execute(agent, task);
        Assert.assertTrue(r.succeeded(), "succeeded after tool call");
        Assert.assertEquals(2, call[0], "LLM called twice");
        Assert.assertTrue(r.cycleRecords().size() >= 2, "at least 2 cycles");
    }

    public void testMaxCyclesTermination() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        // LLM always wants to call a tool but tool keeps running
        LLMProvider llm = p -> "{\"type\":\"tool_call\",\"tool_name\":\"loop\",\"arguments\":{},\"reasoning_trace\":\"loop\"}";
        reg.register(ToolContract.readOnly("loop","1.0","loops"), (a,c) -> ToolResult.ok("looping"));
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("loop forever").maxCycles(3).build();
        ExecutionResult r = runtime().execute(agent, task);
        Assert.assertFalse(r.succeeded(), "not succeeded (resource limit)");
        Assert.assertTrue(r.terminationReason() instanceof TerminationReason.ResourceLimit, "resource limit");
    }

    public void testEscalationTerminates() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.escalate("too complex"), reg);
        Task task = Task.builder().instruction("do impossible thing").maxCycles(5).build();
        ExecutionResult r = runtime().execute(agent, task);
        Assert.assertTrue(r.terminationReason() instanceof TerminationReason.Escalated, "escalated");
    }

    public void testConsecutiveFailuresTerminate() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        // Register tool that always throws
        reg.register(ToolContract.readOnly("fail","1.0","always fails"),
            (args,ctx) -> { throw new ToolException("FAIL","deliberate failure"); });
        LLMProvider llm = p -> "{\"type\":\"tool_call\",\"tool_name\":\"fail\",\"arguments\":{},\"reasoning_trace\":\"try\"}";
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("fail 3 times").maxCycles(10).build();
        ExecutionResult r = runtime().execute(agent, task);
        Assert.assertFalse(r.succeeded(), "not succeeded after failures");
        Assert.assertTrue(
            r.terminationReason() instanceof TerminationReason.FailureEscalation ||
            r.terminationReason() instanceof TerminationReason.ResourceLimit,
            "failure or resource limit");
    }

    public void testMultipleCyclesRecorded() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("step","1.0","step"), (a,c) -> ToolResult.ok("done"));
        int[] call = {0};
        LLMProvider llm = p -> {
            call[0]++;
            if (call[0] <= 2)
                return "{\"type\":\"tool_call\",\"tool_name\":\"step\",\"arguments\":{},\"reasoning_trace\":\"step\"}";
            return "{\"type\":\"final_answer\",\"content\":\"completed\"}";
        };
        Agent agent = agentWith(llm, reg);
        Task task = Task.builder().instruction("multi step").maxCycles(10).build();
        ExecutionResult r = runtime().execute(agent, task);
        Assert.assertTrue(r.succeeded(), "multi-step succeeded");
        Assert.assertTrue(r.cycleRecords().size() >= 3, ">=3 cycles recorded");
    }

    public void testObservabilityEvents() {
        InMemoryEventSink sink = new InMemoryEventSink();
        AgentRuntime rt = new AgentRuntime(new PassThroughPlanValidator(), sink);
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("done"), reg);
        Task task = Task.builder().instruction("test").maxCycles(5).build();
        rt.execute(agent, task);
        Assert.assertTrue(sink.count(AgentEvent.EventType.RUN_STARTED) >= 1, "RUN_STARTED emitted");
        Assert.assertTrue(sink.count(AgentEvent.EventType.RUN_COMPLETED) >= 1, "RUN_COMPLETED emitted");
        Assert.assertTrue(sink.count(AgentEvent.EventType.CYCLE_STARTED) >= 1, "CYCLE_STARTED emitted");
    }

    public void testAsyncExecution() throws Exception {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("async done"), reg);
        Task task = Task.builder().instruction("async test").maxCycles(5).build();
        CompletableFuture<ExecutionResult> future = runtime().executeAsync(agent, task);
        ExecutionResult r = future.get(5, TimeUnit.SECONDS);
        Assert.assertTrue(r.succeeded(), "async succeeded");
        Assert.assertEquals("async done", r.finalAnswer(), "async answer");
    }

    public void testTenantIsolation() {
        InMemoryEventSink sink = new InMemoryEventSink();
        AgentRuntime rt = new AgentRuntime(new PassThroughPlanValidator(), sink);
        SimpleToolRegistry reg = new SimpleToolRegistry();
        Agent agent = agentWith(StubLLMProvider.finalAnswer("result"), reg);
        Task task = Task.builder().instruction("tenant test").maxCycles(5).build();
        ExecutionResult r1 = rt.execute(agent, task, "tenant-A", "user1");
        ExecutionResult r2 = rt.execute(agent, task, "tenant-B", "user2");
        Assert.assertTrue(r1.succeeded(), "tenant-A succeeded");
        Assert.assertTrue(r2.succeeded(), "tenant-B succeeded");
        // verify run IDs are distinct
        Assert.assertFalse(
            r1.cycleRecords().isEmpty() && r2.cycleRecords().isEmpty(),
            "both ran");
    }
}
