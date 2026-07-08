package com.agentframework.tests;

import com.agentframework.action.SimpleToolRegistry;
import com.agentframework.action.ToolContract;
import com.agentframework.core.DefaultExecutionContext;
import com.agentframework.foundation.*;
import com.agentframework.reasoning.LLMReasoning;
import com.agentframework.reasoning.PromptBuilder;
import com.agentframework.reasoning.StubLLMProvider;
import com.agentframework.reasoning.strategy.PlanAndExecuteStrategy;
import com.agentframework.reasoning.strategy.ReActStrategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end reasoning scenarios using StubLLMProvider to drive deterministic
 * multi-step flows through the strategies.
 */
public class ReasoningIntegrationTest {

    @Test
    public void planAndExecuteComplexTaskWithStubLLM() {
        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(
                ToolContract.readOnly("echo", "v1", "Echoes the value field"),
                (args, ctx) -> ToolResult.ok("echo:" + args.get("value"))
        );

        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.planJson("sub1", "sub2"))
                .then(StubLLMProvider.executeToolCallJson("echo", "{\"value\":\"v\"}"))
                .then(StubLLMProvider.executeFinalAnswerJson("all done"));

        Task task = Task.builder().instruction("solve complex task").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t-int-1", "user-1");

        Observations observations = Observations.of(java.util.List.of(
                new Observation("obs", Origin.USER, TrustTier.HIGH,
                        Instant.now(), "env")));

        PlanAndExecuteStrategy strategy = PlanAndExecuteStrategy.withDefault();
        PromptBuilder promptBuilder = new PromptBuilder("", registry, 2048);
        LLMReasoning reasoning = new LLMReasoning(llm, strategy, promptBuilder);

        // First step: PLAN → AskClarification with encoded sub-tasks
        Decision d1 = reasoning.decide(ctx, observations);
        assertInstanceOf(AskClarification.class, d1,
                "First decision should be an encoded PLAN AskClarification");
        String payload = ((AskClarification) d1).question();
        assertTrue(payload.startsWith(PlanAndExecuteStrategy.PLAN_PREFIX));
        assertTrue(payload.contains("sub1"));
        assertTrue(payload.contains("sub2"));

        // Simulate the runner having pushed sub-goals; now we are in EXECUTE
        ctx.flagPlanStale(null); // ensure strategy treats next call as EXECUTE

        Decision d2 = reasoning.decide(ctx, observations);
        // EXECUTE path delegates to JsonDecisionParser; our stub sends a
        // tool_call, so we expect a ToolCall here.
        assertInstanceOf(ToolCall.class, d2);

        Decision d3 = strategy.parse(StubLLMProvider.executeFinalAnswerJson("all done"));
        assertInstanceOf(FinalAnswer.class, d3);
        assertEquals("all done", ((FinalAnswer) d3).content());

        assertTrue(llm.callCount() >= 2,
                "stub must have been called at least twice for PLAN and EXECUTE");
    }

    @Test
    public void reactStrategyToolThenFinalAnswer() {
        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(
                ToolContract.readOnly("sum", "v1", "Adds two numbers"),
                (args, ctx) -> {
                    Number a = (Number) args.get("a");
                    Number b = (Number) args.get("b");
                    return ToolResult.ok(a.intValue() + b.intValue());
                }
        );

        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.toolCallJson("sum", "{\"a\":1,\"b\":2}"))
                .then(StubLLMProvider.finalAnswerJson("3"));

        Task task = Task.builder().instruction("add two numbers").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t-int-2", "user-1");

        Observations observations = Observations.empty();

        ReActStrategy strategy = new ReActStrategy();
        PromptBuilder promptBuilder = new PromptBuilder("", registry, 2048);
        LLMReasoning reasoning = new LLMReasoning(llm, strategy, promptBuilder);

        // First reasoning step: stub returns tool_call → ToolCall decision
        Decision d1 = reasoning.decide(ctx, observations);
        assertInstanceOf(ToolCall.class, d1);
        ToolCall tc = (ToolCall) d1;
        assertEquals("sum", tc.toolName());

        // Second step: simulate tool execution and a new observation that could
        // influence the next model call. The stub's second script entry is a
        // final_answer; ReActStrategy will parse it into FinalAnswer.
        Observations obsAfterTool = Observations.of(java.util.List.of(
                new Observation("tool:sum result=3", Origin.TOOL,
                        TrustTier.HIGH, Instant.now(), "sum")));

        Decision d2 = reasoning.decide(ctx, obsAfterTool);
        assertInstanceOf(FinalAnswer.class, d2);
        assertEquals("3", ((FinalAnswer) d2).content());

        assertEquals("stub", llm.name());
        assertTrue(llm.callCount() >= 2, "stub must have been called twice");
    }
}
