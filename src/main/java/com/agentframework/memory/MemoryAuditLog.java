package com.agentframework.memory;
import com.agentframework.core.RequestContext;
import java.time.Duration; import java.util.List;
public interface MemoryAuditLog {
    void record(MemoryAuditEntry entry, RequestContext ctx);
    List<MemoryAuditEntry> historyOf(String recordId);
    List<MemoryAuditEntry> writesBy(String agentId, Duration window);
    List<MemoryAuditEntry> writesFromUntrustedSources();
    List<MemoryAuditEntry> allRecordsForUser(String userId);
}
