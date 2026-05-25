package com.agentframework.integration.llm;

import com.agentframework.integration.http.*;
import com.agentframework.reasoning.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * LLMProvider adapter for OpenAI Chat Completions API.
 * Chat only — for embeddings use {@link OpenAiEmbeddingProvider}.
 */
public final class OpenAiLLMProvider implements LLMProvider {

    private static final String PATH_CHAT      = "/chat/completions";
    private static final String FIELD_MODEL    = "model";
    private static final String FIELD_MESSAGES = "messages";
    private static final String FIELD_TEMP     = "temperature";
    private static final String FIELD_MAX_TOK  = "max_tokens";
    private static final String FIELD_ROLE     = "role";
    private static final String FIELD_CONTENT  = "content";
    private static final String FIELD_CHOICES  = "choices";
    private static final String FIELD_MESSAGE  = "message";

    private final LLMProviderConfig config;
    private final JsonHttpClient    http;
    private final AuthStrategy      auth;

    public OpenAiLLMProvider(LLMProviderConfig config) {
        this.config = config;
        this.http   = new JsonHttpClient(config.policy());
        this.auth   = AuthStrategy.bearer(config.apiKey());
    }

    @Override
    public String name() { return "openai"; }

    @Override
    public String generate(Prompt prompt) {
        ObjectNode body = http.newObject();
        body.put(FIELD_MODEL, config.model());
        body.put(FIELD_TEMP,  prompt.parameters().temperature());
        body.put(FIELD_MAX_TOK, prompt.parameters().maxTokens());

        ArrayNode messages = body.putArray(FIELD_MESSAGES);
        for (Message m : prompt.messages()) {
            ObjectNode msg = messages.addObject();
            msg.put(FIELD_ROLE,    RoleMapper.toOpenAiStyle(m.role()));
            msg.put(FIELD_CONTENT, m.content());
        }

        JsonNode response = http.post(
                config.baseUrl() + PATH_CHAT, body, auth);
        return response
                .path(FIELD_CHOICES).path(0)
                .path(FIELD_MESSAGE).path(FIELD_CONTENT)
                .asText();
    }
}
