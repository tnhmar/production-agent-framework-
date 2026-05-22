package com.agentframework.foundation;
public record Escalate(String reason, String severity) implements Decision {}
