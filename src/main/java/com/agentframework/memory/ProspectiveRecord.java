package com.agentframework.memory;
import java.time.Instant;
public record ProspectiveRecord(String id, String userId, String content,
        Trigger trigger, Instant createdAt, boolean fired, Instant firedAt) {}
