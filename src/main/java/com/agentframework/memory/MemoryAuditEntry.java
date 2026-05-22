package com.agentframework.memory;
import java.time.Duration; import java.time.Instant; import java.util.Set;
public record MemoryAuditEntry(
        String auditId, String operation, String recordId,
        String userId, String agentId, String sessionId,
        Instant timestamp, String source, String contentHash,
        double importance, String resolution, Duration ttl, Set<String> tags) {}
