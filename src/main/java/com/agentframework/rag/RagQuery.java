package com.agentframework.rag;
import java.util.Map;
public record RagQuery(String naturalLanguage, int topK, Map<String,String> filters) {}
