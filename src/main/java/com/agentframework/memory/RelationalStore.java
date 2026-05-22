package com.agentframework.memory;
import com.agentframework.core.RequestContext;
import java.util.List; import java.util.Map; import java.util.Optional;
public interface RelationalStore {
    void insert(MemoryRecord record, RequestContext ctx);
    void update(String id, MemoryRecord record, RequestContext ctx);
    Optional<MemoryRecord> get(String id, RequestContext ctx);
    List<MemoryRecord> bm25Search(String query, int k, RequestContext ctx);
    List<MemoryRecord> queryByMetadata(Map<String,String> filters, RequestContext ctx);
    void delete(String id, RequestContext ctx);
}
