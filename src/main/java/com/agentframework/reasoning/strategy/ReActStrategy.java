package com.agentframework.reasoning.strategy;
import com.agentframework.foundation.*;
import com.agentframework.reasoning.ReasoningStrategy;
import java.util.*;
import java.util.regex.*;
public class ReActStrategy implements ReasoningStrategy {
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
            String type = field(json, "type");
            return switch (type != null ? type : "") {
                case "tool_call" -> {
                    String name  = field(json, "tool_name");
                    String trace = field(json, "reasoning_trace");
                    yield new ToolCall(name != null ? name : "unknown",
                                       parseArgs(json), trace != null ? trace : "");
                }
                case "final_answer" -> {
                    String c = field(json, "content");
                    yield new FinalAnswer(c != null ? c : "", List.of());
                }
                case "escalate" -> {
                    String r = firstNonNull(field(json,"reasoning_trace"), field(json,"content"), "unspecified");
                    yield new Escalate(r, "MEDIUM");
                }
                case "ask_clarification" -> {
                    String q = field(json, "content");
                    yield new AskClarification(q != null ? q : "Please clarify.");
                }
                default -> new Escalate("Unknown type: " + type, "HIGH");
            };
        } catch (Exception e) {
            return new Escalate("Parse failed: " + e.getMessage(), "HIGH");
        }
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
        return (s >= 0 && e > s) ? raw.substring(s, e+1) : raw;
    }

    private String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private Map<String,Object> parseArgs(String json) {
        Map<String,Object> map = new LinkedHashMap<>();
        int s = json.indexOf("\"arguments\"");
        if (s < 0) return map;
        int ob = json.indexOf('{', s), cb = json.indexOf('}', ob);
        if (ob < 0 || cb < 0) return map;
        String inner = json.substring(ob+1, cb);
        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?[\\d.]+)|(true|false))").matcher(inner);
        while (m.find()) {
            String k = m.group(1);
            if      (m.group(2) != null) map.put(k, m.group(2));
            else if (m.group(3) != null) map.put(k, Double.parseDouble(m.group(3)));
            else if (m.group(4) != null) map.put(k, Boolean.parseBoolean(m.group(4)));
        }
        return map;
    }

    private String firstNonNull(String... vals) {
        for (String v : vals) if (v != null) return v;
        return "";
    }
}
