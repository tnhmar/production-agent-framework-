package com.agentframework.tests;

import com.agentframework.action.SimpleToolRegistry;
import com.agentframework.core.DefaultExecutionContext;
import com.agentframework.foundation.Decision;
import com.agentframework.foundation.Observation;
import com.agentframework.foundation.Observations;
import com.agentframework.foundation.Origin;
import com.agentframework.foundation.Task;
import com.agentframework.foundation.Tool;
import com.agentframework.foundation.ToolResult;
import com.agentframework.memory.WorkingMemory;
import com.agentframework.reasoning.LLMReasoning;
import com.agentframework.reasoning.Prompt;
import com.agentframework.reasoning.StubLLMProvider;
import com.agentframework.reasoning.strategy.PlanAndExecuteStrategy;
import com.agentframework.reasoning.strategy.ReActStrategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end scenarios that exercise multiple reasoning strategies, the
 * StateMachineRunner loop, tool registry, and WorkingMemory using
 * StubLLMProvider as the deterministic LLM.
 */
public class ReasoningIntegrationTest {

    /**
     * Complex task that forces: PLAN -> EXECUTE(tool_call) -> EXECUTE(final_answer)
     * via PlanAndExecuteStrategy with a multi-step StubLLMProvider script.
     */
    @Test
    public void planAndExecuteComplexTaskWithStubLLM() {
        // Tool that just echoes its args into a ToolResult
        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() { return "echo"; }

            @Override
            public ToolResult invoke(Map<String, Object> args) {
                return ToolResult.success("echo:" + args.get("value"));
            }
        });

        // PLAN with two subtasks, then EXECUTE/tool_call, then EXECUTE/final_answer
        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.planJson("sub1", "sub2"))
                .then(StubLLMProvider.executeToolCallJson("echo", "{\"value\":\"v\"}"))
                .then(StubLLMProvider.executeFinalAnswerJson("all done"));

        Task task = Task.builder().instruction("solve complex task").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t-int-1", "user-1");

        Observations observations = Observations.of(List.of(
                new Observation("obs", Origin.USER, com.agentframework.foundation.TrustTier.HIGH,
                        Instant.now(), "env")));

        PlanAndExecuteStrategy strategy = new PlanAndExecuteStrategy();
        LLMReasoning reasoning = new LLMReasoning(llm, strategy, registry);

        Decision finalDecision = reasoning.run(ctx, observations, WorkingMemory.create());

        assertInstanceOf(com.agentframework.foundation.FinalAnswer.class, finalDecision,
                "final decision must be FinalAnswer for EXECUTE/final_answer");
        assertEquals("all done", ((com.agentframework.foundation.FinalAnswer) finalDecision).content());

        assertTrue(llm.callCount() >= 3,
                "stub must have been called for PLAN and both EXECUTE steps");
    }

    /**
     * ReActStrategy end-to-end: StubLLMProvider routes first to a tool_call
     * and then to a final_answer once the tool result has been observed.
     */
    @Test
    public void reactStrategyToolThenFinalAnswer() {
        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() { return "sum"; }

            @Override
            public ToolResult invoke(Map<String, Object> args) {
                Number a = (Number) args.get("a");
                Number b = (Number) args.get("b");
                return ToolResult.success("" + (a.intValue() + b.intValue()));
            }
        });

        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.toolCallJson("sum", "{\"a\":1,\"b\":2}"))
                .then(StubLLMProvider.finalAnswerJson("3"));

        Task task = Task.builder().instruction("add two numbers").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t-int-2", "user-1");

        Observations observations = Observations.empty();

        ReActStrategy strategy = new ReActStrategy();
        LLMReasoning reasoning = new LLMReasoning(llm, strategy, registry);

        Decision finalDecision = reasoning.run(ctx, observations, WorkingMemory.create());

        assertInstanceOf(com.agentframework.foundation.FinalAnswer.class, finalDecision);
        assertEquals("3", ((com.agentframework.foundation.FinalAnswer) finalDecision).content());
        assertEquals("stub", llm.name());
        assertTrue(llm.callCount() >= 2, "at least two calls: one for tool, one for answer");
    }
}
