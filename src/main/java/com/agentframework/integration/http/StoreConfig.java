package com.agentframework.integration.http;

import java.util.Objects;

/**
 * Immutable configuration value object for vector/graph store connections.
 * Embed a {@link ConnectionPolicy} for timeout and retry settings.
 */
public record StoreConfig(
        String apiKey,
        String host,
        String namespace,
        ConnectionPolicy policy) {

    public StoreConfig {
        Objects.requireNonNull(apiKey,     "apiKey");
        Objects.requireNonNull(host,       "host");
        Objects.requireNonNull(namespace,  "namespace");
        Objects.requireNonNull(policy,     "policy");
    }

    public int timeoutSeconds() { return policy.timeoutSeconds(); }
    public int maxRetries()     { return policy.maxRetries(); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String apiKey    = "";
        private String host      = "";
        private String namespace = "";
        private ConnectionPolicy policy = ConnectionPolicy.defaults();

        public Builder apiKey(String v)           { this.apiKey     = v; return this; }
        public Builder host(String v)             { this.host       = v; return this; }
        public Builder namespace(String v)        { this.namespace  = v; return this; }
        public Builder policy(ConnectionPolicy v) { this.policy     = v; return this; }
        public StoreConfig build() {
            return new StoreConfig(apiKey, host, namespace, policy);
        }
    }
}
