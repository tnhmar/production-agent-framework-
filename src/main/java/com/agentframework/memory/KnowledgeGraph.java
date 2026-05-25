package com.agentframework.memory;

import com.agentframework.core.RequestContext;
import com.agentframework.integration.http.StoreConfig;
import com.agentframework.integration.store.*;
import java.util.List;
import java.util.Objects;

/**
 * Port for triple-based knowledge graph storage and traversal.
 *
 * <pre>{@code
 * KnowledgeGraph g = KnowledgeGraph.neo4j(config);
 * KnowledgeGraph g = KnowledgeGraph.neptune(config);
 * }</pre>
 */
public interface KnowledgeGraph {

    void upsert(Triple triple, RequestContext ctx);

    List<Triple> query(String subject, String predicate, String object, RequestContext ctx);

    List<Triple> traverse(String startSubject, List<String> predicates,
                           int maxDepth, RequestContext ctx);

    void delete(Triple triple, RequestContext ctx);

    void deleteBySubject(String subject, RequestContext ctx);

    static KnowledgeGraph neo4j(StoreConfig config) {
        Objects.requireNonNull(config, "config");
        return new Neo4jKnowledgeGraph(config);
    }

    static KnowledgeGraph neo4j(StoreConfig config, String database) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(database, "database");
        return new Neo4jKnowledgeGraph(config, database);
    }

    static KnowledgeGraph neptune(StoreConfig config) {
        Objects.requireNonNull(config, "config");
        return new NeptuneKnowledgeGraph(config);
    }
}
