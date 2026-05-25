package com.agentframework.integration.llm;

import com.agentframework.integration.http.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * EmbeddingProvider adapter for Ollama local embedding API.
 * No authentication required.
 */
public final class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final String PATH_EMBED   = "/api/embeddings";
    private static final String FIELD_MODEL  = "model";
    private static final String FIELD_PROMPT = "prompt";
    private static final String FIELD_EMBED  = "embedding";

    private final LLMProviderConfig config;
    private final JsonHttpClient    http;

    public OllamaEmbeddingProvider(LLMProviderConfig config) {
        this.config = config;
        this.http   = new JsonHttpClient(config.policy());
    }

    @Override public String name() { return "ollama-embedding"; }

    @Override
    public List<Double> embed(String text) {
        ObjectNode body = http.newObject();
        body.put(FIELD_MODEL,  config.model());
        body.put(FIELD_PROMPT, text);

        JsonNode response = http.post(
                config.baseUrl() + PATH_EMBED, body, AuthStrategy.none());

        JsonNode arr = response.path(FIELD_EMBED);
        List<Double> result = new ArrayList<>(arr.size());
        for (JsonNode v : arr) result.add(v.asDouble());
        return List.copyOf(result);
    }
}
