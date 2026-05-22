package com.agentframework.memory;
import com.agentframework.core.RequestContext;
import java.time.Duration; import java.util.List;
public interface Memory {
    String write(MemoryContent content, MemoryType type, MemoryMetadata meta, RequestContext ctx);
    List<MemoryRecord> retrieve(MemoryQuery query, int topK, RequestContext ctx);
    void update(String id, MemoryContent content, RequestContext ctx);
    void delete(String id, RequestContext ctx);
    void expire(String id, Duration ttl, RequestContext ctx);
    MemorySummary consolidate(String sessionId, RequestContext ctx);
}
