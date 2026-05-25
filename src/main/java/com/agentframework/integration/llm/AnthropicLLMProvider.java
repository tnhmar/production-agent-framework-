package com.agentframework.integration.llm;

import com.agentframework.integration.http.*;
import com.agentframework.reasoning.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * LLMProvider adapter for Anthropic Messages API.
 */
public final class AnthropicLLMProvider implements LLMProvider {

    private static final String PATH_MESSAGES  = "/messages";
    private static final String FIELD_MODEL    = "model";
    private static final String FIELD_SYSTEM   = "system";
    private static final String FIELD_MESSAGES = "messages";
    private static final String FIELD_MAX_TOK  = "max_tokens";
    private static final String FIELD_ROLE     = "role";
    private static final String FIELD_CONTENT  = "content";
    private static final String FIELD_TYPE     = "type";
    private static final String FIELD_TEXT     = "text";

    private final LLMProviderConfig config;
    private final JsonHttpClient    http;
    private final AuthStrategy      auth;

    public AnthropicLLMProvider(LLMProviderConfig config) {
        this.config = config;
        this.http   = new JsonHttpClient(config.policy());
        this.auth   = AuthStrategy.anthropic(config.apiKey());
    }

    @Override public String name() { return "anthropic"; }

    @Override
    public String generate(Prompt prompt) {
        ObjectNode body = http.newObject();
        body.put(FIELD_MODEL,   config.model());
        body.put(FIELD_MAX_TOK, prompt.parameters().maxTokens());

        // Lift SYSTEM messages to top-level system field (Anthropic requirement)
        prompt.messages().stream()
              .filter(m -> m.role() == Message.Role.SYSTEM)
              .findFirst()
              .ifPresent(m -> body.put(FIELD_SYSTEM, m.content()));

        ArrayNode messages = body.putArray(FIELD_MESSAGES);
        for (Message m : prompt.messages()) {
            if (m.role() == Message.Role.SYSTEM) continue; // already lifted
            ObjectNode msg = messages.addObject();
            msg.put(FIELD_ROLE, RoleMapper.toAnthropicStyle(m.role()));
            ArrayNode parts = msg.putArray(FIELD_CONTENT);
            ObjectNode part = parts.addObject();
            part.put(FIELD_TYPE, "text");
            part.put(FIELD_TEXT, m.content());
        }

        JsonNode response = http.post(
                config.baseUrl() + PATH_MESSAGES, body, auth);
        return response
                .path(FIELD_CONTENT).path(0)
                .path(FIELD_TEXT).asText();
    }
}
