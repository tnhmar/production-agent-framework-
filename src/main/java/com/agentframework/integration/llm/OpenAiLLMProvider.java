package com.agentframework.integration.llm;

import com.agentframework.integration.http.*;
import com.agentframework.reasoning.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.stream.Stream;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        ObjectNode body = buildBody(prompt, false);
        JsonNode response = http.post(
                config.baseUrl() + PATH_CHAT, body, auth);
        return response
                .path(FIELD_CHOICES).path(0)
                .path(FIELD_MESSAGE).path(FIELD_CONTENT)
                .asText();
    }

    @Override
    public Stream<String> generateStream(Prompt prompt) {
        ObjectNode body = buildBody(prompt, true);

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + PATH_CHAT))
                .timeout(java.time.Duration.ofSeconds(config.policy().timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        auth.apply(rb);

        // ofLines() returns a Stream<String> of SSE lines as they arrive.
        return http.sendLines(rb.build())
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).trim())
                .takeWhile(json -> !"[DONE]".equals(json))
                .map(json -> {
                    try {
                        JsonNode node = MAPPER.readTree(json);
                        JsonNode delta = node.path("choices").path(0).path("delta");
                        return delta.path("content").asText("");
                    } catch (JsonProcessingException e) {
                        return ""; // malformed SSE frame — skip token
                    }
                })
                .filter(token -> !token.isEmpty());
    }

    private ObjectNode buildBody(Prompt prompt, boolean stream) {
        ObjectNode body = http.newObject();
        body.put(FIELD_MODEL, config.model());
        body.put(FIELD_TEMP,  prompt.parameters().temperature());
        body.put(FIELD_MAX_TOK, prompt.parameters().maxTokens());
        body.put("stream", stream);

        ArrayNode messages = body.putArray(FIELD_MESSAGES);
        for (Message m : prompt.messages()) {
            ObjectNode msg = messages.addObject();
            msg.put(FIELD_ROLE,    RoleMapper.toOpenAiStyle(m.role()));
            msg.put(FIELD_CONTENT, m.content());
        }
        return body;
    }
}
