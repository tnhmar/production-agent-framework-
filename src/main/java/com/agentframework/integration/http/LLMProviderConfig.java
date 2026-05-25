package com.agentframework.integration.http;

import java.util.Objects;

/**
 * Immutable configuration value object for LLM provider connections.
 * Embed a {@link ConnectionPolicy} for timeout and retry settings.
 */
public record LLMProviderConfig(
        String apiKey,
        String baseUrl,
        String model,
        ConnectionPolicy policy) {

    public LLMProviderConfig {
        Objects.requireNonNull(apiKey,   "apiKey");
        Objects.requireNonNull(baseUrl,  "baseUrl");
        Objects.requireNonNull(model,    "model");
        Objects.requireNonNull(policy,   "policy");
    }

    /** Convenience accessor kept for backward compatibility. */
    public int timeoutSeconds() { return policy.timeoutSeconds(); }
    public int maxRetries()     { return policy.maxRetries(); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String apiKey   = "";
        private String baseUrl  = "https://api.openai.com/v1";
        private String model    = "gpt-4o";
        private ConnectionPolicy policy = ConnectionPolicy.defaults();

        public Builder apiKey(String v)           { this.apiKey  = v; return this; }
        public Builder baseUrl(String v)          { this.baseUrl = v; return this; }
        public Builder model(String v)            { this.model   = v; return this; }
        public Builder policy(ConnectionPolicy v) { this.policy  = v; return this; }
        public LLMProviderConfig build() {
            return new LLMProviderConfig(apiKey, baseUrl, model, policy);
        }
    }
}
