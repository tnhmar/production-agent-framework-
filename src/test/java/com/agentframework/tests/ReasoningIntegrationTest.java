package com.agentframework.tests;

import com.agentframework.action.SimpleToolRegistry;
import com.agentframework.action.ToolContract;
import com.agentframework.core.DefaultExecutionContext;
import com.agentframework.foundation.Decision;
import com.agentframework.foundation.FinalAnswer;
import com.agentframework.foundation.Observation;
import com.agentframework.foundation.Observations;
import com.agentframework.foundation.Origin;
import com.agentframework.foundation.Task;
import com.agentframework.foundation.ToolResult;
import com.agentframework.reasoning.LLMReasoning;
import com.agentframework.reasoning.PromptBuilder;
import com.agentframework.reasoning.StubLLMProvider;
import com.agentframework.reasoning.strategy.PlanAndExecuteStrategy;
import com.agentframework.reasoning.strategy.ReActStrategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end scenarios that exercise multiple reasoning strategies using
 * StubLLMProvider as the deterministic LLM.
 */
public class ReasoningIntegrationTest {

    /**
     * Complex task that forces: PLAN -> EXECUTE(tool_call) -> EXECUTE(final_answer)
     * via PlanAndExecuteStrategy with a multi-step StubLLMProvider script.
     */
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
                new Observation("obs", Origin.USER, com.agentframework.foundation.TrustTier.HIGH,
                        Instant.now(), "env")));

        PlanAndExecuteStrategy strategy = PlanAndExecuteStrategy.withDefault();
        PromptBuilder promptBuilder = new PromptBuilder("", registry, 2048);
        LLMReasoning reasoning = new LLMReasoning(llm, strategy, promptBuilder);

        Decision finalDecision = reasoning.decide(ctx, observations);

        assertInstanceOf(FinalAnswer.class, finalDecision,
                "final decision must be FinalAnswer for EXECUTE/final_answer");
        assertEquals("all done", ((FinalAnswer) finalDecision).content());

        assertTrue(llm.callCount() >= 1,
                "stub must have been called at least once for this strategy");
    }

    /**
     * ReActStrategy end-to-end: StubLLMProvider routes first to a tool_call
     * and then to a final_answer once the tool result has been observed.
     */
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

        Decision finalDecision = reasoning.decide(ctx, observations);

        assertInstanceOf(FinalAnswer.class, finalDecision);
        assertEquals("3", ((FinalAnswer) finalDecision).content());
        assertEquals("stub", llm.name());
        assertTrue(llm.callCount() >= 1, "stub must have been called at least once");
    }
}
