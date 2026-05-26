package com.agentframework.reasoning.strategy;

import com.agentframework.foundation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * Shared, Jackson-based Decision parser used by all reasoning strategies.
 *
 * <p>Design invariants:
 * <ul>
 *   <li>Zero hardcoded strings — all literals live in {@link DecisionType} and
 *       {@link Severity} inner enums.</li>
 *   <li>Never throws — every parse failure is surfaced as a HIGH-severity
 *       {@link Escalate} so callers always receive a valid {@link Decision}.</li>
 *   <li>Strips markdown fences and finds the outermost JSON object via
 *       brace-depth tracking, not naive indexOf/lastIndexOf.</li>
 *   <li>Preserves original {@code tool_name} casing from JSON — the tool
 *       registry is case-sensitive and must not be normalised here.</li>
 * </ul>
 */
public final class JsonDecisionParser {

    // ── Decision type literals ──────────────────────────────────────────────
    public enum DecisionType {
        TOOL_CALL("tool_call"),
        FINAL_ANSWER("final_answer"),
        ESCALATE("escalate"),
        ASK_CLARIFICATION("ask_clarification");

        public final String jsonValue;
        DecisionType(String v) { this.jsonValue = v; }

        public static Optional<DecisionType> from(String v) {
            if (v == null) return Optional.empty();
            for (DecisionType d : values())
                if (d.jsonValue.equalsIgnoreCase(v)) return Optional.of(d);
            return Optional.empty();
        }
    }

    // ── Severity literals ──────────────────────────────────────────────────
    public enum Severity {
        LOW("LOW"), MEDIUM("MEDIUM"), HIGH("HIGH");
        public final String value;
        Severity(String v) { this.value = v; }
    }

    // ── JSON field name constants ──────────────────────────────────────────
    private static final String FIELD_TYPE            = "type";
    private static final String FIELD_TOOL_NAME       = "tool_name";
    private static final String FIELD_ARGUMENTS       = "arguments";
    private static final String FIELD_REASONING_TRACE = "reasoning_trace";
    private static final String FIELD_CONTENT         = "content";

    // ── Fallback literals ──────────────────────────────────────────────────
    private static final String DEFAULT_CLARIFICATION = "Please clarify.";
    private static final String DEFAULT_TOOL_NAME     = "unknown";
    private static final String EMPTY_TRACE           = "";
    private static final String EMPTY_JSON_OBJ        = "{}";

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private JsonDecisionParser() {}

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Parses a raw LLM string into a {@link Decision}.
     * Never throws; returns {@link Escalate} with HIGH severity on any failure.
     */
    public static Decision parse(String raw) {
        String json = extractJson(raw);
        try {
            JsonNode root = MAPPER.readTree(json);
            return fromNode(root);
        } catch (Exception e) {
            return escalate(Severity.HIGH,
                "JSON parse failure: " + e.getMessage());
        }
    }

    /** Parses from an already-resolved {@link JsonNode}. Never throws. */
    public static Decision fromNode(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull())
            return escalate(Severity.HIGH, "Null or missing JSON node");

        String typeRaw = text(root, FIELD_TYPE);
        DecisionType type = DecisionType.from(typeRaw)
            .orElse(null);

        if (type == null)
            return escalate(Severity.HIGH,
                "Unknown decision type: " + typeRaw);

        return switch (type) {
            case TOOL_CALL -> {
                String name  = text(root, FIELD_TOOL_NAME);
                String trace = text(root, FIELD_REASONING_TRACE);
                yield new ToolCall(
                    name  != null ? name  : DEFAULT_TOOL_NAME,
                    parseArguments(root.path(FIELD_ARGUMENTS)),
                    trace != null ? trace : EMPTY_TRACE);
            }
            case FINAL_ANSWER -> {
                String content = text(root, FIELD_CONTENT);
                yield new FinalAnswer(
                    content != null ? content : EMPTY_TRACE,
                    List.of());
            }
            case ESCALATE -> {
                String reason = firstNonNull(
                    text(root, FIELD_REASONING_TRACE),
                    text(root, FIELD_CONTENT),
                    "unspecified");
                yield escalate(Severity.MEDIUM, reason);
            }
            case ASK_CLARIFICATION -> {
                String q = text(root, FIELD_CONTENT);
                yield new AskClarification(
                    q != null && !q.isBlank() ? q : DEFAULT_CLARIFICATION);
            }
        };
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private static Escalate escalate(Severity s, String reason) {
        return new Escalate(reason, s.value);
    }

    /**
     * Strips markdown fences and locates the outermost JSON object via
     * brace-depth tracking — robust to leading/trailing prose.
     */
    static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return EMPTY_JSON_OBJ;

        // Strip ```json ... ``` or ``` ... ``` fences
        String s = raw.replaceAll("(?s)```(?:json)?\\s*(\\{.*?\\})\\s*```", "$1").trim();

        // Brace-depth scan for outermost object
        int depth = 0, start = -1, end = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if      (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0) { end = i; break; } }
        }
        if (start >= 0 && end > start) return s.substring(start, end + 1);
        return EMPTY_JSON_OBJ;
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return (n.isMissingNode() || n.isNull()) ? null : n.asText();
    }

    private static Map<String, Object> parseArguments(JsonNode argsNode) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (argsNode == null || argsNode.isMissingNode() || !argsNode.isObject())
            return map;
        argsNode.fields().forEachRemaining(entry -> {
            JsonNode v = entry.getValue();
            Object   val;
            if      (v.isTextual())              val = v.asText();
            else if (v.isBoolean())              val = v.asBoolean();
            else if (v.isIntegralNumber())        val = v.longValue();
            else if (v.isFloatingPointNumber())   val = v.doubleValue();
            else if (v.isArray() || v.isObject()) val = v.toString();
            else                                  val = v.asText();
            map.put(entry.getKey(), val);
        });
        return Collections.unmodifiableMap(map);
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
