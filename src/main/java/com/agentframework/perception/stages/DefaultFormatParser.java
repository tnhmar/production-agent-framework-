package com.agentframework.perception.stages;
import java.util.List; import java.util.Map;
public class DefaultFormatParser implements FormatParser {
    public ParsedContent parse(Object raw, InputType type) {
        return new ParsedContent(raw != null ? raw.toString() : "", Map.of(), List.of());
    }
}
