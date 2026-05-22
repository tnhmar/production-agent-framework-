package com.agentframework.perception.stages;
import java.util.List; import java.util.Map;
public record ParsedContent(String textContent, Map<String,Object> structured, List<Attachment> attachments) {
    public static ParsedContent text(String t) { return new ParsedContent(t, Map.of(), List.of()); }
}
