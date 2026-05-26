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
 * <p>The zero-arg Builder default and the backward-compatible null-to-default
 * handling ensure existing callers that do not set
 * {@code anthropicVersion} continue to work unchanged.
 */
public record LLMProviderConfig(
        String apiKey,
        String baseUrl,
        String model,
        String anthropicVersion,   // M3 fix
        ConnectionPolicy policy) 