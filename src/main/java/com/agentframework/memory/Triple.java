package com.agentframework.memory;
public record Triple(String subject, String predicate, String object,
        String provenance, double confidence) {}
