package com.agentframework.memory.impl;
import com.agentframework.memory.*;
import java.util.List;
import java.util.regex.Pattern;
public class PatternWriteGate implements WriteGate {
    private final List<Pattern> forbidden;
    public PatternWriteGate(List<String> regexes) {
        forbidden = regexes.stream().map(Pattern::compile).toList();
    }
    public static PatternWriteGate defaultSensitiveData() {
        // credit card and SSN patterns
        return new PatternWriteGate(List.of(
            "\\b\\d{4}-\\d{4}-\\d{4}-\\d{4}\\b",
            "\\b\\d{3}-\\d{2}-\\d{4}\\b"
        ));
    }
    public WriteDecision evaluate(MemoryContent content, MemoryMetadata meta) {
        String text = content.text();
        for (Pattern p : forbidden) if (p.matcher(text).find()) return WriteDecision.REJECT;
        return WriteDecision.ACCEPT;
    }
}
