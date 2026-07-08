package com.agentframework.reasoning;

import com.agentframework.integration.http.LLMProviderConfig;
import com.agentframework.integration.llm.*;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Port for LLM text generation.
 *
 * <pre>{@code
 * LLMProvider llm = LLMProvider.openAi(config);
 * LLMProvider llm = LLMProvider.anthropic(config);
 * }</pre>
 */
public interface LLMProvider {

    /**
     * Synchronous, single-shot generation entry point used by all existing
     * strategies. Implementations must remain blocking and return the full
     * model output as a single string.
     */
    String generate(Prompt prompt);

    /**
     * Optional streaming entry point used for final answers.
     *
     * <p>Default implementation delegates to {@link #generate(Prompt)} and
     * returns a character-by-character {@link Stream}. This preserves
     * behaviour for existing adapters while allowing streaming-aware
     * implementations to override with a provider-specific streaming API
     * (SSE, chunked HTTP, etc.).
     */
    default Stream<String> generateStream(Prompt prompt) {
        String full = generate(prompt);
        return full.chars().mapToObj(c -> String.valueOf((char) c));
    }

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
