package com.agentframework.integration.llm;

import com.agentframework.integration.http.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * EmbeddingProvider adapter for OpenAI Embeddings API.
 * Separate from {@link OpenAiLLMProvider} so chat and embedding models
 * can be configured and substituted independently.
 */
public final class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private static final String PATH_EMBED    = "/embeddings";
    private static final String FIELD_INPUT   = "input";
    private static final String FIELD_MODEL   = "model";
    private static final String FIELD_DATA    = "data";
    private static final String FIELD_EMBED   = "embedding";

    private final LLMProviderConfig config;
    private final JsonHttpClient    http;
    private final AuthStrategy      auth;

    public OpenAiEmbeddingProvider(LLMProviderConfig config) {
        this.config = config;
        this.http   = new JsonHttpClient(config.policy());
        this.auth   = AuthStrategy.bearer(config.apiKey());
    }

    @Override
    public String name() { return "openai-embedding"; }

    @Override
    public List<Double> embed(String text) {
        ObjectNode body = http.newObject();
        body.put(FIELD_INPUT, text);
        body.put(FIELD_MODEL, config.model());

        JsonNode response = http.post(
                config.baseUrl() + PATH_EMBED, body, auth);

        JsonNode embeddingArray = response.path(FIELD_DATA).path(0).path(FIELD_EMBED);
        List<Double> result = new ArrayList<>(embeddingArray.size());
        for (JsonNode v : embeddingArray) result.add(v.asDouble());
        return List.copyOf(result);
    }
}
