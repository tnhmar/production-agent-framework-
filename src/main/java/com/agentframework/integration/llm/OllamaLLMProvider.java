package com.agentframework.integration.llm;

import com.agentframework.integration.http.*;
import com.agentframework.reasoning.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * LLMProvider adapter for Ollama local inference API.
 * No authentication required — uses {@link AuthStrategy#none()}.
 */
public final class OllamaLLMProvider implements LLMProvider {

    private static final String PATH_CHAT      = "/api/chat";
    private static final String FIELD_MODEL    = "model";
    private static final String FIELD_MESSAGES = "messages";
    private static final String FIELD_STREAM   = "stream";
    private static final String FIELD_ROLE     = "role";
    private static final String FIELD_CONTENT  = "content";
    private static final String FIELD_MESSAGE  = "message";

    private final LLMProviderConfig config;
    private final JsonHttpClient    http;

    public OllamaLLMProvider(LLMProviderConfig config) {
        this.config = config;
        this.http   = new JsonHttpClient(config.policy());
    }

    @Override public String name() { return "ollama"; }

    @Override
    public String generate(Prompt prompt) {
        ObjectNode body = http.newObject();
        body.put(FIELD_MODEL,  config.model());
        body.put(FIELD_STREAM, false);

        ArrayNode messages = body.putArray(FIELD_MESSAGES);
        for (Message m : prompt.messages()) {
            ObjectNode msg = messages.addObject();
            msg.put(FIELD_ROLE,    RoleMapper.toOpenAiStyle(m.role()));
            msg.put(FIELD_CONTENT, m.content());
        }

        JsonNode response = http.post(
                config.baseUrl() + PATH_CHAT, body, AuthStrategy.none());
        return response
                .path(FIELD_MESSAGE).path(FIELD_CONTENT)
                .asText();
    }
}
