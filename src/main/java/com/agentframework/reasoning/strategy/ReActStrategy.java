package com.agentframework.reasoning.strategy;

import com.agentframework.foundation.*;
import com.agentframework.reasoning.ReasoningStrategy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * ReAct (Reasoning + Acting) strategy.
 *
 * <p>Parses LLM output as a JSON decision object. Uses Jackson for robust parsing —
 * handles nested arguments, escaped strings, markdown fences, and whitespace variations.
 *
 * <p>Expected output schema:
 * <pre>
 * {
 *   "type": "tool_call" | "final_answer" | "escalate" | "ask_clarification",
 *   "tool_name":       "<name>",        // required for tool_call
 *   "arguments":       { ... },         // required for tool_call
 *   "reasoning_trace": "<why>",
 *   "content":         "<text>"         // required for final_answer / ask_clarification
 * }
 * </pre>
 */
public class ReActStrategy implements ReasoningStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules();

    public String outputSchemaDescription() {
        return """
            Output exactly one JSON object:
            {
              "type": "tool_call" | "final_answer" | "escalate" | "ask_clarification",
              "tool_name": "<name>",          // required for tool_call
              "arguments":  { ... },          // required for tool_call
              "reasoning_trace": "<why>",
              "content": "<text>"             // required for final_answer / ask_clarification
            }
            """;
    }

    public Decision parse(String llmOutput) {
        String json = extractJson(llmOutput);
        try {
            JsonNode root = MAPPER.readTree(json);
            String type = text(root, "type");
            return switch (type != null ? type : "") {
                case "tool_call" -> {
                    String name  = text(root, "tool_name");
                    String trace = text(root, "reasoning_trace");
                    yield new ToolCall(
                        name != null ? name : "unknown",
                        parseArgs(root.path("arguments")),
                        trace != null ? trace : "");
                }
                case "final_answer" -> {
                    String c = text(root, "content");
                    yield new FinalAnswer(c != null ? c : "", List.of());
                }
                case "escalate" -> {
                    String r = firstNonNull(text(root, "reasoning_trace"), text(root, "content"), "unspecified");
                    yield new Escalate(r, "MEDIUM");
                }
                case "ask_clarification" -> {
                    String q = text(root, "content");
                    yield new AskClarification(q != null ? q : "Please clarify.");
                }
                default -> new Escalate("Unknown type: " + type, "HIGH");
            };
        } catch (JsonProcessingException e) {
            return new Escalate("ReAct parse failed: " + e.getMessage(), "HIGH");
        }
    }

    /** Strips markdown fences and finds the first top-level JSON object. */
    private String extractJson(String raw) {
        if (raw == null) return "{}";
        // Strip markdown code fences: ```json ... ``` or ``` ... ```
        String stripped = raw.replaceAll("(?s)```(?:json)?\\s*(\\{.*?\\})\\s*```", "$1").trim();
        // Find outermost { }
        int depth = 0, start = -1, end = -1;
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0) { end = i; break; } }
        }
        if (start >= 0 && end > start) return stripped.substring(start, end + 1);
        // Fallback: use raw if it starts with {
        int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
        return (s >= 0 && e > s) ? raw.substring(s, e + 1) : "{}";
    }

    private String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private Map<String, Object> parseArgs(JsonNode argsNode) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (argsNode == null || argsNode.isMissingNode() || !argsNode.isObject()) return map;
        argsNode.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if      (v.isTextual())  map.put(e.getKey(), v.asText());
            else if (v.isBoolean())  map.put(e.getKey(), v.asBoolean());
            else if (v.isNumber())   map.put(e.getKey(), v.numberValue());
            else if (v.isArray() || v.isObject()) map.put(e.getKey(), v.toString());
            else map.put(e.getKey(), v.asText());
        });
        return map;
    }

    private String firstNonNull(String... vals) {
        for (String v : vals) if (v != null) return v;
        return "";
    }
}
