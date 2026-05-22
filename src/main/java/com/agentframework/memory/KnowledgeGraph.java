package com.agentframework.memory;
import com.agentframework.core.RequestContext;
import java.util.List;
public interface KnowledgeGraph {
    void upsert(Triple triple, RequestContext ctx);
    List<Triple> query(String subject, String predicate, String object, RequestContext ctx);
    List<Triple> traverse(String startSubject, List<String> predicates, int maxDepth, RequestContext ctx);
    void delete(Triple triple, RequestContext ctx);
    void deleteBySubject(String subject, RequestContext ctx);
}
