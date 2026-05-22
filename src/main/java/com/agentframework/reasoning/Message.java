package com.agentframework.reasoning;
public record Message(Role role, String content) {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL_RESULT }
}
