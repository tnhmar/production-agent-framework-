package com.agentframework.tests;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.agentframework.foundation.*;
import com.agentframework.reasoning.*;
import com.agentframework.reasoning.strategy.ReActStrategy;
import java.util.List;
import java.util.Map;
public class ReasoningTest {

    private final ReActStrategy strategy = new ReActStrategy();

    @Test
    public void testParseFinalAnswer() {
        String json = "{\"type\":\"final_answer\",\"content\":\"The answer is 42\",\"reasoning_trace\":\"computed\"}";
        Decision d = strategy.parse(json);
        assertTrue(d instanceof FinalAnswer, "is FinalAnswer");
        assertEquals("The answer is 42", ((FinalAnswer)d).content(), "content");
    }

    @Test
    public void testParseToolCall() {
        String json = "{\"type\":\"tool_call\",\"tool_name\":\"search\",\"arguments\":{\"query\":\"java streams\"},\"reasoning_trace\":\"need info\"}";
        Decision d = strategy.parse(json);
        assertTrue(d instanceof ToolCall, "is ToolCall");
        ToolCall tc = (ToolCall)d;
        assertEquals("search", tc.toolName(), "tool name");
        assertEquals("java streams", tc.arguments().get("query"), "query arg");
        assertEquals("need info", tc.reasoningTrace(), "trace");
    }

    @Test
    public void testParseEscalate() {
        String json = "{\"type\":\"escalate\",\"reasoning_trace\":\"cannot proceed\"}";
        Decision d = strategy.parse(json);
        assertTrue(d instanceof Escalate, "is Escalate");
        assertEquals("cannot proceed", ((Escalate)d).reason(), "reason");
    }

    @Test
    public void testParseAskClarification() {
        String json = "{\"type\":\"ask_clarification\",\"content\":\"What format?\"}";
        Decision d = strategy.parse(json);
        assertTrue(d instanceof AskClarification, "is AskClarification");
        assertEquals("What format?", ((AskClarification)d).question(), "question");
    }

    @Test
    public void testParseMalformedFallsToEscalate() {
        Decision d = strategy.parse("{not json}}}");
        assertTrue(d instanceof Escalate, "malformed -> Escalate");
    }

    @Test
    public void testParseUnknownTypeFallsToEscalate() {
        String json = "{\"type\":\"dance\",\"content\":\"boogie\"}";
        Decision d = strategy.parse(json);
        assertTrue(d instanceof Escalate, "unknown type -> Escalate");
        assertTrue(((Escalate)d).reason().contains("Unknown type"), "message mentions unknown type");
    }

    @Test
    public void testParseJsonWithMarkdownFences() {
        String wrapped = "Here is my decision:\n\"{\"type\":\"final_answer\",\"content\":\"done\"}\"";
        // The strategy looks for first { to last }
        Decision d = strategy.parse(wrapped);
        assertTrue(d instanceof FinalAnswer, "handles quotes around json");
    }

    @Test
    public void testStubLLMFinalAnswer() {
        StubLLMProvider llm = StubLLMProvider.finalAnswer("task complete");
        ReActStrategy strat = new ReActStrategy();
        Decision d = strat.decide(llm, Prompt.user("go"));
        assertTrue(d instanceof FinalAnswer, "stub -> FinalAnswer");
        assertEquals("task complete", ((FinalAnswer)d).content(), "content");
    }

    @Test
    public void testStubLLMToolCall() {
        StubLLMProvider llm = StubLLMProvider.toolCall("search","{\"q\":\"test\"}");
        ReActStrategy strat = new ReActStrategy();
        Decision d = strat.decide(llm, Prompt.user("search for test"));
        assertTrue(d instanceof ToolCall, "stub -> ToolCall");
        assertEquals("search", ((ToolCall)d).toolName(), "tool name");
    }

    @Test
    public void testStubLLMEscalate() {
        StubLLMProvider llm = StubLLMProvider.escalate("too complex");
        Decision d = new ReActStrategy().decide(llm, Prompt.user("do impossible"));
        assertTrue(d instanceof Escalate, "stub -> Escalate");
        assertTrue(((Escalate)d).reason().contains("complex"), "reason");
    }

    @Test
    public void testOutputSchemaDescription() {
        String schema = strategy.outputSchemaDescription();
        assertTrue(schema.contains("tool_call"), "schema mentions tool_call");
        assertTrue(schema.contains("final_answer"), "schema mentions final_answer");
        assertTrue(schema.contains("escalate"), "schema mentions escalate");
    }
}
