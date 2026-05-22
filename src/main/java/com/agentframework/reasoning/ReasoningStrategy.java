package com.agentframework.reasoning;
import com.agentframework.foundation.Decision;
public interface ReasoningStrategy {
    String outputSchemaDescription();
    Decision parse(String llmOutput);
    default Decision decide(LLMProvider llm, Prompt prompt) {
        return parse(llm.generate(prompt));
    }
}
