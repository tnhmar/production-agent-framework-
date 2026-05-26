package com.agentframework.integration.http;

import java.util.Objects;

/**
 * Immutable configuration value object for LLM provider connections.
 * Embed a {@link ConnectionPolicy} for timeout and retry settings.
 *
 * <h3>M3 fix — configurable Anthropic API version</h3>
 * <p>Added {@link #anthropicVersion} field with a safe default of
 * {@link #DEFAULT_ANTHROPIC_VERSION}.  When constructing an Anthropic
 * provider, pass this value to
 * {@link AuthStrategy#anthropic(String, String)} so the API version can be
 * updated via configuration without recompilation.
 *
 * <p>The zero-arg Builder default and the null-to-default handling ensure
 * existing callers that do not set {@code anthropicVersion} continue to
 * work unchanged.
 */
public record LLMProviderConfig(
        String apiKey,
        String baseUrl,
        String model,
        String anthropicVersion,   // M3 fix
        ConnectionPolicy policy) {

    /** Default Anthropic API version used when none is explicitly configured. */
    public static final String DEFAULT_ANTHROPIC_VERSION =
        AuthStrategy.AnthropicVersion.STABLE.value;

    public LLMProviderConfig {
        Objects.requireNonNull(apiKey,   "apiKey");
        Objects.requireNonNull(baseUrl,  "baseUrl");
        Objects.requireNonNull(model,    "model");
        Objects.requireNonNull(policy,   "policy");
        // M3 fix: default to STABLE if not provided
        if (anthropicVersion == null || anthropicVersion.isBlank())
            anthropicVersion = DEFAULT_ANTHROPIC_VERSION;
    }

    /** Convenience accessor kept for backward compatibility. */
    public int timeoutSeconds() { return policy.timeoutSeconds(); }
    public int maxRetries()     { return policy.maxRetries(); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String           apiKey           = "";
        private String           baseUrl          = "https://api.openai.com/v1";
        private String           model            = "gpt-4o";
        private String           anthropicVersion = DEFAULT_ANTHROPIC_VERSION; // M3 fix
        private ConnectionPolicy policy           = ConnectionPolicy.defaults();

        public Builder apiKey(String v)              { this.apiKey           = v; return this; }
        public Builder baseUrl(String v)             { this.baseUrl          = v; return this; }
        public Builder model(String v)               { this.model            = v; return this; }
        public Builder anthropicVersion(String v)    { this.anthropicVersion = v; return this; } // M3 fix
        public Builder policy(ConnectionPolicy v)    { this.policy           = v; return this; }

        public LLMProviderConfig build() {
            return new LLMProviderConfig(apiKey, baseUrl, model, anthropicVersion, policy);
        }
    }
}
