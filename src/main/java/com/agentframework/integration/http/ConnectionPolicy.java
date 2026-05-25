package com.agentframework.integration.http;

import java.util.Objects;

/**
 * Shared timeout and retry policy embedded by both
 * {@link LLMProviderConfig} and {@link StoreConfig}.
 * Centralises the policy so changing retry semantics touches one place.
 */
public record ConnectionPolicy(int timeoutSeconds, int maxRetries) {

    public ConnectionPolicy {
        if (timeoutSeconds < 1)
            throw new IllegalArgumentException("timeoutSeconds must be >= 1");
        if (maxRetries < 0)
            throw new IllegalArgumentException("maxRetries must be >= 0");
    }

    public static ConnectionPolicy defaults() {
        return new ConnectionPolicy(30, 2);
    }

    public ConnectionPolicy withTimeout(int seconds) {
        return new ConnectionPolicy(seconds, maxRetries);
    }

    public ConnectionPolicy withRetries(int retries) {
        return new ConnectionPolicy(timeoutSeconds, retries);
    }
}
