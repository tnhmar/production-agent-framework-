package com.agentframework.reasoning;

import com.agentframework.integration.http.LLMProviderConfig;
import com.agentframework.integration.llm.*;
import java.util.Objects;

/**
 * Port for LLM text generation.
 *
 * <pre>{@code
 * LLMProvider llm = LLMProvider.openAi(config);
 * LLMProvider llm = LLMProvider.anthropic(config);
 * }</pre>
 */
public interface LLMProvider {

    String generate(Prompt prompt);

    /** Stable logical provider identifier. Override in every adapter. */
    default String name() { return getClass().getSimpleName(); }

    static LLMProvider openAi(LLMProviderConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.apiKey().isBlank())
            throw new IllegalArgumentException("OpenAI requires a non-blank apiKey");
        return new OpenAiLLMProvider(config);
    }

    static LLMProvider anthropic(LLMProviderConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.apiKey().isBlank())
            throw new IllegalArgumentException("Anthropic requires a non-blank apiKey");
        return new AnthropicLLMProvider(config);
    }

    static LLMProvider googleGemini(LLMProviderConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.apiKey().isBlank())
            throw new IllegalArgumentException("Google Gemini requires a non-blank apiKey");
        return new GoogleGeminiLLMProvider(config);
    }

    static LLMProvider mistral(LLMProviderConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.apiKey().isBlank())
            throw new IllegalArgumentException("Mistral requires a non-blank apiKey");
        return new MistralLLMProvider(config);
    }

    static LLMProvider ollama(LLMProviderConfig config) {
        Objects.requireNonNull(config, "config");
        return new OllamaLLMProvider(config);
    }
}
