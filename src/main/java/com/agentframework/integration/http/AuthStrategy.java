package com.agentframework.integration.http;

import java.net.http.HttpRequest;

/**
 * Strategy for attaching authentication credentials to an HTTP request.
 * Keeps {@link JsonHttpClient} provider-agnostic — it never knows which
 * auth scheme is in use.
 *
 * <pre>{@code
 * AuthStrategy auth = AuthStrategy.bearer(config.apiKey());
 * AuthStrategy auth = AuthStrategy.apiKey(config.apiKey());   // Pinecone
 * AuthStrategy auth = AuthStrategy.none();                    // Ollama / Neptune VPC
 * }</pre>
 */
@FunctionalInterface
public interface AuthStrategy {

    void apply(HttpRequest.Builder requestBuilder);

    /** Standard OAuth2 Bearer token (OpenAI, Anthropic, Mistral, Gemini, Weaviate). */
    static AuthStrategy bearer(String token) {
        return rb -> rb.header("Authorization", "Bearer " + token);
    }

    /**
     * Pinecone-style API key header.
     * Official spec: {@code Api-Key: {apiKey}}
     */
    static AuthStrategy pineconeApiKey(String apiKey) {
        return rb -> rb.header("Api-Key", apiKey);
    }

    /** Anthropic-style header pair (x-api-key + anthropic-version). */
    static AuthStrategy anthropic(String apiKey) {
        return rb -> rb
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01");
    }

    /** No authentication (Ollama local, Neptune VPC-internal). */
    static AuthStrategy none() {
        return rb -> {};
    }
}
