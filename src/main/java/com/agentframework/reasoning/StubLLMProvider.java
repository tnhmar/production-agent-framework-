package com.agentframework.reasoning;
/** Deterministic LLM for testing — returns a fixed response. */
public class StubLLMProvider implements LLMProvider {
    private final String response;
    public StubLLMProvider(String response) { this.response = response; }

    public static StubLLMProvider finalAnswer(String text) {
        return new StubLLMProvider(
            "{\"type\":\"final_answer\",\"content\":\"" + text.replace("\"","'") + "\",\"reasoning_trace\":\"done\"}");
    }
    public static StubLLMProvider toolCall(String tool, String argsJson) {
        return new StubLLMProvider(
            "{\"type\":\"tool_call\",\"tool_name\":\"" + tool + "\",\"arguments\":" + argsJson + ",\"reasoning_trace\":\"calling\"}");
    }
    public static StubLLMProvider escalate(String reason) {
        return new StubLLMProvider(
            "{\"type\":\"escalate\",\"reasoning_trace\":\"" + reason.replace("\"","'") + "\"}");
    }
    public String generate(Prompt p) { return response; }
    public String name()             { return "stub"; }
}
