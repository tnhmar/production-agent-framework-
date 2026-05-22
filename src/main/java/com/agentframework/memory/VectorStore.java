package com.agentframework.memory;
import com.agentframework.core.RequestContext;
import java.util.List;
public interface VectorStore {
    void insert(String id, List<Double> embedding, String payload, RequestContext ctx);
    List<Neighbor> search(List<Double> query, int k, RequestContext ctx);
    void delete(String id, RequestContext ctx);
    String getPayload(String id, RequestContext ctx);
}
