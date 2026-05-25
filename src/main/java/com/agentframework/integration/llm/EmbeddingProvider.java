package com.agentframework.integration.llm;

import com.agentframework.integration.http.LLMProviderConfig;
import java.util.List;
import java.util.Objects;

/**
 * Port for converting text into a dense embedding vector.
 *
 * <pre>{@code
 * EmbeddingProvider emb = EmbeddingProvider.openAi(config);
 * EmbeddingProvider emb = EmbeddingProvider.ollama(config);
 * }</pre>
 */
@FunctionalInterface
public interface EmbeddingProvider {

    /**
     * Embeds the given text into a dense float vector.
     *
     * @param text non-null, non-blank input text
     * @return unmodifiable embedding vector; never null, never empty
     */
    List<Double> embed(String text);

    default String name() { return getClass().getSimpleName(); }

    static EmbeddingProvider openAi(LLMProviderConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.apiKey().isBlank())
            throw new IllegalArgumentException("OpenAI embedding requires a non-blank apiKey");
        return new OpenAiEmbeddingProvider(config);
    }

    static EmbeddingProvider ollama(LLMProviderConfig config) {
        Objects.requireNonNull(config, "config");
        return new OllamaEmbeddingProvider(config);
    }
}
