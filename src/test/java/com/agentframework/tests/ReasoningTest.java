package com.agentframework.tests;
import com.agentframework.foundation.*;
import com.agentframework.reasoning.*;
import com.agentframework.reasoning.strategy.ReActStrategy;
import com.agentframework.testutil.Assert;
import java.util.List;
import java.util.Map;
public class ReasoningTest {

    private final ReActStrategy strategy = new ReActStrategy();

    public void testParseFinalAnswer() {
        String json = "{\"type\":\"final_answer\",\"content\":\"The answer is 42\",\"reasoning_trace\":\"computed\"}";
        Decision d = strategy.parse(json);
        Assert.assertTrue(d instanceof FinalAnswer, "is FinalAnswer");
        Assert.assertEquals("The answer is 42", ((FinalAnswer)d).content(), "content");
    }

    public void testParseToolCall() {
        String json = "{\"type\":\"tool_call\",\"tool_name\":\"search\",\"arguments\":{\"query\":\"java streams\"},\"reasoning_trace\":\"need info\"}";
        Decision d = strategy.parse(json);
        Assert.assertTrue(d instanceof ToolCall, "is ToolCall");
        ToolCall tc = (ToolCall)d;
        Assert.assertEquals("search", tc.toolName(), "tool name");
        Assert.assertEquals("java streams", tc.arguments().get("query"), "query arg");
        Assert.assertEquals("need info", tc.reasoningTrace(), "trace");
    }

    public void testParseEscalate() {
        String json = "{\"type\":\"escalate\",\"reasoning_trace\":\"cannot proceed\"}";
        Decision d = strategy.parse(json);
        Assert.assertTrue(d instanceof Escalate, "is Escalate");
        Assert.assertEquals("cannot proceed", ((Escalate)d).reason(), "reason");
    }

    public void testParseAskClarification() {
        String json = "{\"type\":\"ask_clarification\",\"content\":\"What format?\"}";
        Decision d = strategy.parse(json);
        Assert.assertTrue(d instanceof AskClarification, "is AskClarification");
        Assert.assertEquals("What format?", ((AskClarification)d).question(), "question");
    }

    public void testParseMalformedFallsToEscalate() {
        Decision d = strategy.parse("{not json}}}");
        Assert.assertTrue(d instanceof Escalate, "malformed -> Escalate");
    }

    public void testParseUnknownTypeFallsToEscalate() {
        String json = "{\"type\":\"dance\",\"content\":\"boogie\"}";
        Decision d = strategy.parse(json);
        Assert.assertTrue(d instanceof Escalate, "unknown type -> Escalate");
        Assert.assertContains(((Escalate)d).reason(), "Unknown type", "message mentions unknown type");
    }

    public void testParseJsonWithMarkdownFences() {
        String wrapped = "Here is my decision:\n\"{\"type\":\"final_answer\",\"content\":\"done\"}\"";
        // The strategy looks for first { to last }
        Decision d = strategy.parse(wrapped);
        Assert.assertTrue(d instanceof FinalAnswer, "handles quotes around json");
    }

    public void testStubLLMFinalAnswer() {
        StubLLMProvider llm = StubLLMProvider.finalAnswer("task complete");
        ReActStrategy strat = new ReActStrategy();
        Decision d = strat.decide(llm, Prompt.user("go"));
        Assert.assertTrue(d instanceof FinalAnswer, "stub -> FinalAnswer");
        Assert.assertEquals("task complete", ((FinalAnswer)d).content(), "content");
    }

    public void testStubLLMToolCall() {
        StubLLMProvider llm = StubLLMProvider.toolCall("search","{\"q\":\"test\"}");
        ReActStrategy strat = new ReActStrategy();
        Decision d = strat.decide(llm, Prompt.user("search for test"));
        Assert.assertTrue(d instanceof ToolCall, "stub -> ToolCall");
        Assert.assertEquals("search", ((ToolCall)d).toolName(), "tool name");
    }

    public void testStubLLMEscalate() {
        StubLLMProvider llm = StubLLMProvider.escalate("too complex");
        Decision d = new ReActStrategy().decide(llm, Prompt.user("do impossible"));
        Assert.assertTrue(d instanceof Escalate, "stub -> Escalate");
        Assert.assertContains(((Escalate)d).reason(), "complex", "reason");
    }

    public void testOutputSchemaDescription() {
        String schema = strategy.outputSchemaDescription();
        Assert.assertContains(schema, "tool_call", "schema mentions tool_call");
        Assert.assertContains(schema, "final_answer", "schema mentions final_answer");
        Assert.assertContains(schema, "escalate", "schema mentions escalate");
    }
}
