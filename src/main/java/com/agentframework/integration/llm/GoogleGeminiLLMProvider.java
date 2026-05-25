package com.agentframework.integration.llm;

import com.agentframework.integration.http.*;
import com.agentframework.reasoning.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * LLMProvider adapter for Google Gemini generateContent API.
 * API key is sent via Authorization header — never in the URL query string.
 */
public final class GoogleGeminiLLMProvider implements LLMProvider {

    private static final String PATH_TEMPLATE    = "/models/%s:generateContent";
    private static final String FIELD_SYSTEM_INS = "systemInstruction";
    private static final String FIELD_CONTENTS   = "contents";
    private static final String FIELD_ROLE       = "role";
    private static final String FIELD_PARTS      = "parts";
    private static final String FIELD_TEXT       = "text";
    private static final String FIELD_GEN_CFG    = "generationConfig";
    private static final String FIELD_MAX_TOK    = "maxOutputTokens";
    private static final String FIELD_TEMP       = "temperature";
    private static final String FIELD_CANDIDATES = "candidates";
    private static final String FIELD_CONTENT    = "content";

    private final LLMProviderConfig config;
    private final JsonHttpClient    http;
    private final AuthStrategy      auth;
    private final String            url;

    public GoogleGeminiLLMProvider(LLMProviderConfig config) {
        this.config = config;
        this.http   = new JsonHttpClient(config.policy());
        // API key via Authorization header — never in the URL
        this.auth   = AuthStrategy.bearer(config.apiKey());
        this.url    = config.baseUrl() + String.format(PATH_TEMPLATE, config.model());
    }

    @Override public String name() { return "google-gemini"; }

    @Override
    public String generate(Prompt prompt) {
        ObjectNode body = http.newObject();

        // Lift SYSTEM message into systemInstruction
        prompt.messages().stream()
              .filter(m -> m.role() == Message.Role.SYSTEM)
              .findFirst()
              .ifPresent(m -> {
                  ObjectNode si = body.putObject(FIELD_SYSTEM_INS);
                  ArrayNode parts = si.putArray(FIELD_PARTS);
                  parts.addObject().put(FIELD_TEXT, m.content());
              });

        ArrayNode contents = body.putArray(FIELD_CONTENTS);
        for (Message m : prompt.messages()) {
            if (m.role() == Message.Role.SYSTEM) continue;
            ObjectNode turn = contents.addObject();
            turn.put(FIELD_ROLE, RoleMapper.toGeminiStyle(m.role()));
            ArrayNode parts = turn.putArray(FIELD_PARTS);
            parts.addObject().put(FIELD_TEXT, m.content());
        }

        ObjectNode cfg = body.putObject(FIELD_GEN_CFG);
        cfg.put(FIELD_MAX_TOK, prompt.parameters().maxTokens());
        cfg.put(FIELD_TEMP,    prompt.parameters().temperature());

        JsonNode response = http.post(url, body, auth);
        return response
                .path(FIELD_CANDIDATES).path(0)
                .path(FIELD_CONTENT).path(FIELD_PARTS).path(0)
                .path(FIELD_TEXT).asText();
    }
}
