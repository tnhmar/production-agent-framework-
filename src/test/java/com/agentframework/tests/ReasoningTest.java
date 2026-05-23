package com.agentframework.tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.agentframework.action.SimpleToolRegistry;
import com.agentframework.core.DefaultExecutionContext;
import com.agentframework.foundation.*;
import com.agentframework.reasoning.*;
import com.agentframework.reasoning.strategy.ReActStrategy;

import java.util.List;
import java.util.Map;

public class ReasoningTest {

    private final ReActStrategy strategy = new ReActStrategy();

    // ── Original tests (preserved) ────────────────────────────────────────────

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
        assertEquals("search",       tc.toolName(),              "tool name");
        assertEquals("java streams", tc.arguments().get("query"),"query arg");
        assertEquals("need info",    tc.reasoningTrace(),        "trace");
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
        Decision d = strategy.parse(wrapped);
        assertTrue(d instanceof FinalAnswer, "handles quotes around json");
    }

    @Test
    public void testStubLLMFinalAnswer() {
        StubLLMProvider llm = StubLLMProvider.finalAnswer("task complete");
        Decision d = new ReActStrategy().decide(llm, Prompt.user("go"));
        assertTrue(d instanceof FinalAnswer, "stub -> FinalAnswer");
        assertEquals("task complete", ((FinalAnswer)d).content(), "content");
    }

    @Test
    public void testStubLLMToolCall() {
        StubLLMProvider llm = StubLLMProvider.toolCall("search","{\"q\":\"test\"}");
        Decision d = new ReActStrategy().decide(llm, Prompt.user("search for test"));
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
        assertTrue(schema.contains("tool_call"),   "schema mentions tool_call");
        assertTrue(schema.contains("final_answer"),"schema mentions final_answer");
        assertTrue(schema.contains("escalate"),    "schema mentions escalate");
    }

    // ── Extended branch-coverage tests ────────────────────────────────────────

    @Test
    public void testParseNullOutputFallsToEscalate() {
        Decision d = strategy.parse(null);
        assertInstanceOf(Escalate.class, d, "null input -> Escalate");
    }

    @Test
    public void testParseEmptyObjectUnknownType() {
        Decision d = strategy.parse("{}");
        assertInstanceOf(Escalate.class, d, "empty object -> Escalate (unknown type)");
    }

    @Test
    public void testParseRealMarkdownFencedJson() {
        String fenced = "```json\n{\"type\":\"final_answer\",\"content\":\"fenced\"}\n```";
        Decision d = strategy.parse(fenced);
        assertInstanceOf(FinalAnswer.class, d, "real markdown fenced json parsed");
        assertEquals("fenced", ((FinalAnswer)d).content());
    }

    @Test
    public void testParseToolCallBooleanAndNumberArgs() {
        String json = "{\"type\":\"tool_call\",\"tool_name\":\"calc\","
                + "\"arguments\":{\"enabled\":true,\"count\":42,\"ratio\":3.14},"
                + "\"reasoning_trace\":\"t\"}";
        Decision d = strategy.parse(json);
        assertInstanceOf(ToolCall.class, d);
        ToolCall tc = (ToolCall) d;
        assertEquals(Boolean.TRUE, tc.arguments().get("enabled"), "boolean arg");
        assertEquals(42,           ((Number) tc.arguments().get("count")).intValue(),    "int arg");
        assertEquals(3.14,         ((Number) tc.arguments().get("ratio")).doubleValue(), 1e-9, "double arg");
    }

    @Test
    public void testParseToolCallArrayArg() {
        String json = "{\"type\":\"tool_call\",\"tool_name\":\"batch\","
                + "\"arguments\":{\"ids\":[\"a\",\"b\"]},\"reasoning_trace\":\"\"}";
        Decision d = strategy.parse(json);
        assertInstanceOf(ToolCall.class, d);
        assertTrue(((ToolCall) d).arguments().get("ids").toString().contains("a"),
                "array arg serialised and retrievable");
    }

    @Test
    public void testParseAskClarificationNullContentUsesDefault() {
        String json = "{\"type\":\"ask_clarification\"}";
        Decision d = strategy.parse(json);
        assertInstanceOf(AskClarification.class, d);
        assertEquals("Please clarify.", ((AskClarification) d).question(),
                "absent content -> default question");
    }

    @Test
    public void testParseEscalateContentFallbackWhenNoReasoningTrace() {
        String json = "{\"type\":\"escalate\",\"content\":\"too risky\"}";
        Decision d = strategy.parse(json);
        assertInstanceOf(Escalate.class, d);
        assertEquals("too risky", ((Escalate) d).reason(),
                "content used as fallback reason when reasoning_trace absent");
    }

    @Test
    public void testPromptBuilderBuildBasic() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        PromptBuilder pb = new PromptBuilder("You are an agent.", reg, 4096);

        Task task = Task.builder().instruction("do X").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");

        // TrustTier values: HIGH, MEDIUM, LOW, UNTRUSTED — no TRUSTED constant
        Observations obs = Observations.of(List.of(
                new Observation("env", Origin.USER, TrustTier.HIGH, "hello")));

        Prompt prompt = pb.build(ctx, obs, new ReActStrategy());

        assertNotNull(prompt, "prompt must not be null");
        assertFalse(prompt.messages().isEmpty(), "must have at least one message");
        assertTrue(prompt.messages().get(0).content().contains("You are an agent."),
                "system prompt must appear in first message");
    }

    @Test
    public void testPromptBuilderBuildIncludesStaleHint() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        PromptBuilder pb = new PromptBuilder("sys", reg, 1024);

        Task task = Task.builder().instruction("t").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");
        ctx.flagPlanStale("world changed");

        Observations obs = Observations.of(List.of());
        Prompt prompt = pb.build(ctx, obs, new ReActStrategy());

        String sysContent = prompt.messages().get(0).content();
        assertTrue(sysContent.contains("world changed"),
                "staleness hint must appear in system message when plan is stale");
    }
}
