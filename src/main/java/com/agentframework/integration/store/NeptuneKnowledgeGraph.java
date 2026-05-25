package com.agentframework.integration.store;

import com.agentframework.core.RequestContext;
import com.agentframework.integration.http.*;
import com.agentframework.memory.KnowledgeGraph;
import com.agentframework.memory.Triple;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * KnowledgeGraph adapter for Amazon Neptune openCypher HTTP endpoint.
 *
 * All queries are fully parameterised via the "parameters" POST body field.
 * No String.format injection — every user-supplied value goes through params.
 *
 * traverse() with a non-empty predicate filter throws UnsupportedOperationException.
 * Neptune's openCypher does not support dynamic relationship type lists in
 * path expressions. Use Neo4j if predicate-filtered traversal is required.
 */
public final class NeptuneKnowledgeGraph implements KnowledgeGraph {

    private static final String PATH_OPENCYPHER = "/openCypher";

    // Fully parameterised Cypher templates
    private static final String CQL_UPSERT =
            "MERGE (s {id: $subject}) "
          + "MERGE (o {id: $object}) "
          + "MERGE (s)-[r:RELATED {predicate: $predicate}]->(o) "
          + "SET r.provenance=$provenance, r.confidence=$confidence";

    private static final String CQL_QUERY =
            "MATCH (s)-[r:RELATED]->(o) "
          + "WHERE ($subject   IS NULL OR s.id=$subject) "
          + "AND   ($predicate IS NULL OR r.predicate=$predicate) "
          + "AND   ($object    IS NULL OR o.id=$object) "
          + "RETURN s.id AS subject, r.predicate AS predicate, o.id AS object, "
          +        "r.provenance AS provenance, r.confidence AS confidence";

    private static final String CQL_TRAVERSE_ALL =
            "MATCH path = (s {id: $subject})-[:RELATED*1..$maxDepth]->(e) "
          + "UNWIND relationships(path) AS r "
          + "RETURN startNode(r).id AS subject, r.predicate AS predicate, "
          +        "endNode(r).id AS object, r.provenance AS provenance, "
          +        "r.confidence AS confidence";

    private static final String CQL_DELETE =
            "MATCH (s {id: $subject})-[r:RELATED {predicate: $predicate}]->(o {id: $object}) "
          + "DELETE r";

    private static final String CQL_DELETE_SUBJECT =
            "MATCH (s {id: $subject})-[r]->() DELETE r";

    private static final String PARAM_SUBJECT    = "subject";
    private static final String PARAM_OBJECT     = "object";
    private static final String PARAM_PREDICATE  = "predicate";
    private static final String PARAM_PROVENANCE = "provenance";
    private static final String PARAM_CONFIDENCE = "confidence";
    private static final String PARAM_MAX_DEPTH  = "maxDepth";

    private final StoreConfig    config;
    private final JsonHttpClient http;
    private final String         endpoint;

    public NeptuneKnowledgeGraph(StoreConfig config) {
        this.config   = config;
        this.http     = new JsonHttpClient(config.policy());
        this.endpoint = config.host() + PATH_OPENCYPHER;
    }

    /** Neptune inside VPC requires no auth — uses AuthStrategy.none(). */
    private JsonNode execute(String cypher, ObjectNode params) {
        ObjectNode body = http.newObject();
        body.put("query", cypher);
        body.set("parameters", params);
        return http.post(endpoint, body, AuthStrategy.none());
    }

    @Override
    public void upsert(Triple triple, RequestContext ctx) {
        ObjectNode params = http.newObject();
        params.put(PARAM_SUBJECT,    triple.subject());
        params.put(PARAM_OBJECT,     triple.object());
        params.put(PARAM_PREDICATE,  triple.predicate());
        params.put(PARAM_PROVENANCE, triple.provenance());
        params.put(PARAM_CONFIDENCE, triple.confidence());
        execute(CQL_UPSERT, params);
    }

    @Override
    public List<Triple> query(String subject, String predicate,
                               String object, RequestContext ctx) {
        ObjectNode params = http.newObject();
        if (subject   != null) params.put(PARAM_SUBJECT,   subject);
        else                   params.putNull(PARAM_SUBJECT);
        if (predicate != null) params.put(PARAM_PREDICATE, predicate);
        else                   params.putNull(PARAM_PREDICATE);
        if (object    != null) params.put(PARAM_OBJECT,    object);
        else                   params.putNull(PARAM_OBJECT);

        JsonNode response = execute(CQL_QUERY, params);
        return parseTriples(response);
    }

    /**
     * Traversal with predicate filter is not supported by Neptune's openCypher
     * implementation (dynamic relationship type lists in path expressions are
     * unsupported). Use an empty/null predicate list for unfiltered traversal,
     * or switch to Neo4j for predicate-filtered traversal.
     *
     * @throws UnsupportedOperationException if predicates is non-empty
     */
    @Override
    public List<Triple> traverse(String startSubject, List<String> predicates,
                                  int maxDepth, RequestContext ctx) {
        if (predicates != null && !predicates.isEmpty())
            throw new UnsupportedOperationException(
                    "NeptuneKnowledgeGraph does not support predicate-filtered traversal. "
                  + "Use KnowledgeGraph.neo4j(config) for this capability.");

        ObjectNode params = http.newObject();
        params.put(PARAM_SUBJECT,   startSubject);
        params.put(PARAM_MAX_DEPTH, maxDepth);
        JsonNode response = execute(CQL_TRAVERSE_ALL, params);
        return parseTriples(response);
    }

    @Override
    public void delete(Triple triple, RequestContext ctx) {
        ObjectNode params = http.newObject();
        params.put(PARAM_SUBJECT,   triple.subject());
        params.put(PARAM_PREDICATE, triple.predicate());
        params.put(PARAM_OBJECT,    triple.object());
        execute(CQL_DELETE, params);
    }

    @Override
    public void deleteBySubject(String subject, RequestContext ctx) {
        ObjectNode params = http.newObject();
        params.put(PARAM_SUBJECT, subject);
        execute(CQL_DELETE_SUBJECT, params);
    }

    private List<Triple> parseTriples(JsonNode response) {
        List<Triple> result = new ArrayList<>();
        JsonNode results = response.path("results");
        for (JsonNode row : results) {
            result.add(new Triple(
                    row.path(PARAM_SUBJECT).asText(),
                    row.path(PARAM_PREDICATE).asText(),
                    row.path(PARAM_OBJECT).asText(),
                    row.path(PARAM_PROVENANCE).asText(),
                    row.path(PARAM_CONFIDENCE).asDouble()));
        }
        return List.copyOf(result);
    }
}
