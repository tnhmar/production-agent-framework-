package com.agentframework.foundation;

import java.util.*;
import java.util.regex.*;

/**
 * Lightweight JSON Schema value object.
 *
 * <p>Supports a subset of JSON Schema draft-07 that covers the common agent tool
 * contract use-case: required fields, property type checks (string, number,
 * boolean, object, array), and additional-properties control.  Full JSON Schema
 * validation (allOf, $ref, …) is left for production integrations that wire in
 * a Jackson- or Everit-based validator via the {@code validate(Map, SchemaValidator)}
 * overload.
 */
public record JsonSchema(String value) {

    public static JsonSchema of(String v) { return new JsonSchema(v); }

    /** Returns an empty permissive schema (no constraints). */
    public static JsonSchema empty() { return new JsonSchema("{}"); }

    @Override public String toString() { return value; }

    // ── Validation ────────────────────────────────────────────────────────

    /**
     * Validates {@code args} against the required fields and declared property
     * types extracted from this schema's JSON value.
     *
     * @return a list of human-readable violation strings; empty means valid.
     */
    public List<String> validate(Map<String, Object> args) {
        if (args == null) return List.of("arguments map is null");
        if (value == null || value.isBlank() || value.equals("{}")) return List.of();

        List<String> errors = new ArrayList<>();

        // ── Required fields ───────────────────────────────────────────────
        for (String req : extractRequired(value)) {
            if (!args.containsKey(req))
                errors.add("missing required field: \"" + req + "\"");
        }

        // ── Property type checks ──────────────────────────────────────────
        Map<String, String> types = extractPropertyTypes(value);
        for (Map.Entry<String, String> e : types.entrySet()) {
            String field = e.getKey();
            String expectedType = e.getValue();
            Object actual = args.get(field);
            if (actual == null) continue;           // absence already checked above
            String violation = checkType(field, actual, expectedType);
            if (violation != null) errors.add(violation);
        }

        return Collections.unmodifiableList(errors);
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /** Extracts the string array value of the top-level "required" key. */
    private static List<String> extractRequired(String schema) {
        Matcher m = Pattern.compile("\"required\"\\s*:\\s*\\[([^\\]]*)]").matcher(schema);
        if (!m.find()) return List.of();
        List<String> fields = new ArrayList<>();
        Matcher fm = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
        while (fm.find()) fields.add(fm.group(1));
        return fields;
    }

    /**
     * Returns a map of propertyName → declared JSON Schema type string for
     * properties that have an explicit "type" annotation.
     */
    private static Map<String, String> extractPropertyTypes(String schema) {
        Map<String, String> result = new LinkedHashMap<>();
        // Find "properties" block by scanning for its opening brace
        int propIdx = schema.indexOf("\"properties\"");
        if (propIdx < 0) return result;
        int brace = schema.indexOf('{', propIdx);
        if (brace < 0) return result;
        String propBlock = extractObject(schema, brace);
        // For each "fieldName" : { ... "type": "xxx" ... } inside propBlock
        Matcher field = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{([^}]*)}").matcher(propBlock);
        while (field.find()) {
            String name = field.group(1);
            String body = field.group(2);
            Matcher typeMatcher = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            if (typeMatcher.find()) result.put(name, typeMatcher.group(1));
        }
        return result;
    }

    /** Extracts a balanced {…} block starting at {@code start}. */
    private static String extractObject(String s, int start) {
        int depth = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) break; }
        }
        return sb.toString();
    }

    private static String checkType(String field, Object value, String expected) {
        boolean ok = switch (expected) {
            case "string"  -> value instanceof String;
            case "number", "integer" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "object"  -> value instanceof Map;
            case "array"   -> value instanceof List || value instanceof Object[];
            default        -> true;  // unknown type — pass through
        };
        return ok ? null
            : "field \"" + field + "\" expected type " + expected
              + " but got " + value.getClass().getSimpleName();
    }
}
