package com.agentframework.tests;

import com.agentframework.foundation.Decision;
import com.agentframework.foundation.FinalAnswer;
import com.agentframework.foundation.ToolCall;
import com.agentframework.reasoning.InferenceParameters;
import com.agentframework.reasoning.Message;
import com.agentframework.reasoning.Prompt;
import com.agentframework.reasoning.StubLLMProvider;
import com.agentframework.reasoning.strategy.JsonDecisionParser;
import com.agentframework.reasoning.strategy.ReActStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for StubLLMProvider covering its routing modes and JSON helpers.
 */
public class StubLLMProviderTest {

    private final ReActStrategy strategy = new ReActStrategy();

    @Test
    public void sequentialScriptAdvancesThenClamps() {
        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.finalAnswerJson("one"))
                .then(StubLLMProvider.finalAnswerJson("two"));

        Decision d1 = strategy.decide(llm, userPrompt("u1"));
        Decision d2 = strategy.decide(llm, userPrompt("u2"));
        Decision d3 = strategy.decide(llm, userPrompt("u3"));

        assertInstanceOf(FinalAnswer.class, d1);
        assertInstanceOf(FinalAnswer.class, d2);
        assertInstanceOf(FinalAnswer.class, d3);

        assertEquals("one", ((FinalAnswer) d1).content());
        assertEquals("two", ((FinalAnswer) d2).content());
        assertEquals("two", ((FinalAnswer) d3).content(),
                "script clamps at last entry when exhausted");
        assertEquals(3, llm.callCount(), "three generate() calls recorded");
    }

    @Test
    public void onCallOverridesSequentialScriptAtExactIndex() {
        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.finalAnswerJson("base"))
                .then(StubLLMProvider.finalAnswerJson("base2"))
                .onCall(1, StubLLMProvider.finalAnswerJson("override"));

        Decision d0 = strategy.decide(llm, userPrompt("step0"));
        Decision d1 = strategy.decide(llm, userPrompt("step1"));
        Decision d2 = strategy.decide(llm, userPrompt("step2"));

        assertEquals("base",     ((FinalAnswer) d0).content());
        assertEquals("override", ((FinalAnswer) d1).content(),
                "index route wins on matching call index");
        assertEquals("base2",    ((FinalAnswer) d2).content(),
                "sequential script resumes after routed call");
    }

    @Test
    public void whenPromptContainsMatchesLastUserMessage() {
        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.finalAnswerJson("default"))
                .whenPromptContains("special", StubLLMProvider.finalAnswerJson("matched"));

        Decision d1 = strategy.decide(llm, userPrompt("this is special"));

        assertInstanceOf(FinalAnswer.class, d1);
        assertEquals("matched", ((FinalAnswer) d1).content());
    }

    @Test
    public void malformedJsonHelperTriggersHighSeverityEscalate() {
        String bad = StubLLMProvider.malformedJson();
        Decision parsed = JsonDecisionParser.parse(bad);
        assertInstanceOf(com.agentframework.foundation.Escalate.class, parsed);
        com.agentframework.foundation.Escalate esc =
                (com.agentframework.foundation.Escalate) parsed;
        assertEquals("HIGH", esc.severity(),
                "malformedJson is expected to hit HIGH-severity fallback");
    }

    @Test
    public void planAndExecuteJsonHelpersAreWellFormed() {
        String plan = StubLLMProvider.planJson("a", "b");
        assertTrue(plan.contains("\"mode\":\"PLAN\""));
        assertTrue(plan.contains("\"subtasks\""));

        String toolCall = StubLLMProvider.executeToolCallJson("t", "{\"x\":1}");
        Decision d = JsonDecisionParser.parse(toolCall);
        assertInstanceOf(ToolCall.class, d);
        assertEquals("t", ((ToolCall) d).toolName());

        String ans = StubLLMProvider.executeFinalAnswerJson("done");
        Decision d2 = JsonDecisionParser.parse(ans);
        assertInstanceOf(FinalAnswer.class, d2);
        assertEquals("done", ((FinalAnswer) d2).content());
    }

    private static Prompt userPrompt(String content) {
        return new Prompt(
                List.of(new Message(Message.Role.USER, content)),
                InferenceParameters.defaults());
    }
}
