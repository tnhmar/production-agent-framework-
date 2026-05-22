package com.agentframework.reasoning;
public interface LLMProvider {
    String generate(Prompt prompt);
    default String name() { return getClass().getSimpleName(); }
}
