package com.agentframework.integration.http;

import java.net.http.HttpRequest;

/**
 * Strategy for attaching authentication credentials to an HTTP request.
 * Keeps {@link JsonHttpClient} provider-agnostic — it never knows which
 * auth scheme is in use.
 *
 * <pre>{@code
 * AuthStrategy auth = AuthStrategy.bearer(config.apiKey());
 * AuthStrategy auth = AuthStrategy.pineconeApiKey(config.apiKey());
 * AuthStrategy auth = AuthStrategy.anthropic(config.apiKey(), config.anthropicVersion());
 * AuthStrategy auth = AuthStrategy.none();   // Ollama / Neptune VPC
 * }</pre>
 *
 * <h3>M3 fix — configurable Anthropic API version</h3>
 * <p>The one-arg {@link #anthropic(String)} is preserved for backward
 * compatibility; it delegates to the two-arg form using
 * {@link AnthropicVersion#STABLE}.  Production callers should prefer the
 * two-arg form, reading the version from {@link LLMProviderConfig#anthropicVersion()}
 * so the version can be updated via configuration without recompilation.
 */
@FunctionalInterface
public interface AuthStrategy {

    void apply(HttpRequest.Builder requestBuilder);

    // ── Auth schemes ─────────────────────────────────────────────────────────

    /** Standard OAuth2 Bearer token (OpenAI, Mistral, Gemini, Weaviate). */
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

    /**
     * Anthropic two-arg form — preferred in production.
     * Version is read from config so API version upgrades require only a
     * configuration change, not a recompile (M3 fix).
     *
     * @param apiKey           Anthropic API key ({@code x-api-key} header)
     * @param anthropicVersion Anthropic API version string
     *                         (e.g. {@link AnthropicVersion#STABLE})
     */
    static AuthStrategy anthropic(String apiKey, String anthropicVersion) {
        return rb -> rb
                .header("x-api-key", apiKey)
                .header("anthropic-version", anthropicVersion);
    }

    /**
     * Anthropic one-arg form — preserved for backward compatibility.
     * Delegates to {@link #anthropic(String, String)} using
     * {@link AnthropicVersion#STABLE} (M3 fix).
     */
    static AuthStrategy anthropic(String apiKey) {
        return anthropic(apiKey, AnthropicVersion.STABLE.value);
    }

    /** No authentication (Ollama local, Neptune VPC-internal). */
    static AuthStrategy none() {
        return rb -> {};
    }

    // ── M3 fix: versioned Anthropic API version enum ─────────────────────────

    /**
     * Known Anthropic API versions.
     * Update this enum — not call sites — when Anthropic releases a new
     * stable API version.
     */
    enum AnthropicVersion {
        STABLE("2023-06-01"),
        BETA_2024("2024-01-01");

        /** The string value sent in the {@code anthropic-version} header. */
        public final String value;

        AnthropicVersion(String v) { this.value = v; }
    }
}
