package com.agentframework.reasoning;

import com.agentframework.foundation.Decision;

import java.util.stream.Stream;

public interface ReasoningStrategy {

    String outputSchemaDescription();

    Decision parse(String llmOutput);

    default Decision decide(LLMProvider llm, Prompt prompt) {
        return parse(llm.generate(prompt));
    }

    /**
     * Streaming hook for final answers.
     *
     * <p>Called only when the caller has already determined that the next
     * model call should produce a plain-text final answer. The prompt MUST
     * NOT contain the JSON output schema — the returned stream is consumed
     * directly by the caller (UI, SSE, etc.), never fed back into
     * {@link #parse(String)}.
     *
     * <p>Default implementation simply delegates to
     * {@link LLMProvider#generateStream(Prompt)} so existing strategies can
     * opt into streaming without any changes.
     */
    default Stream<String> streamFinalAnswer(LLMProvider llm, Prompt prompt) {
        return llm.generateStream(prompt);
    }
}
