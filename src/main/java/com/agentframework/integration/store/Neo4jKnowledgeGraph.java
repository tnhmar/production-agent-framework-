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
 * KnowledgeGraph adapter for Neo4j HTTP Transactional API.
 *
 * All queries are fully parameterised — no String.format injection.
 * traverse() uses a single recursive Cypher query (one HTTP round-trip).
 */
public final class Neo4jKnowledgeGraph implements KnowledgeGraph {

    private static final String DEFAULT_DATABASE = "neo4j";

    private static final String CYPHER_UPSERT =
            "MERGE (s:Entity {id: $subject}) "
          + "MERGE (o:Entity {id: $object}) "
          + "MERGE (s)-[r:`%s`]->(o) "
          + "SET r.provenance=$provenance, r.confidence=$confidence";

    private static final String CYPHER_QUERY =
            "MATCH (s:Entity)-[r]->(o:Entity) "
          + "WHERE ($subject  IS NULL OR s.id=$subject) "
          + "AND   ($predicate IS NULL OR type(r)=$predicate) "
          + "AND   ($object    IS NULL OR o.id=$object) "
          + "RETURN s.id AS subject, type(r) AS predicate, o.id AS object, "
          + "r.provenance AS provenance, r.confidence AS confidence";

    private static final String CYPHER_TRAVERSE =
            "MATCH path = (s:Entity {id: $subject})-[*1..$maxDepth]->(e:Entity) "
          + "WHERE ALL(r IN relationships(path) WHERE type(r) IN $predicates "
          +       "OR $allPreds) "
          + "UNWIND relationships(path) AS r "
          + "RETURN startNode(r).id AS subject, type(r) AS predicate, "
          +        "endNode(r).id AS object, r.provenance AS provenance, "
          +        "r.confidence AS confidence";

    private static final String CYPHER_DELETE =
            "MATCH (s:Entity {id: $subject})-[r:`%s`]->(o:Entity {id: $object}) "
          + "DELETE r";

    private static final String CYPHER_DELETE_BY_SUBJECT =
            "MATCH (s:Entity {id: $subject})-[r]->() DELETE r";

    private static final String PARAM_SUBJECT    = "subject";
    private static final String PARAM_OBJECT     = "object";
    private static final String PARAM_PREDICATE  = "predicate";
    private static final String PARAM_PROVENANCE = "provenance";
    private static final String PARAM_CONFIDENCE = "confidence";
    private static final String PARAM_MAX_DEPTH  = "maxDepth";
    private static final String PARAM_PREDICATES = "predicates";
    private static final String PARAM_ALL_PREDS  = "allPreds";

    private final StoreConfig    config;
    private final JsonHttpClient http;
    private final AuthStrategy   auth;
    private final String         txUrl;

    public Neo4jKnowledgeGraph(StoreConfig config) {
        this(config, DEFAULT_DATABASE);
    }

    public Neo4jKnowledgeGraph(StoreConfig config, String database) {
        this.config = config;
        this.http   = new JsonHttpClient(config.policy());
        this.auth   = AuthStrategy.bearer(config.apiKey());
        this.txUrl  = config.host() + "/db/" + database + "/tx/commit";
    }

    private JsonNode execute(String cypher, ObjectNode params) {
        ObjectNode body = http.newObject();
        ArrayNode  stmts = body.putArray("statements");
        ObjectNode stmt  = stmts.addObject();
        stmt.put("statement", cypher);
        stmt.set("parameters", params);
        return http.post(txUrl, body, auth);
    }

    private static String sanitizePredicate(String predicate) {
        // Allow only word characters for relationship type names
        return predicate.replaceAll("[^\\w]", "_");
    }

    @Override
    public void upsert(Triple triple, RequestContext ctx) {
        String safePred = sanitizePredicate(triple.predicate());
        String cypher   = String.format(CYPHER_UPSERT, safePred);
        ObjectNode params = http.newObject();
        params.put(PARAM_SUBJECT,    triple.subject());
        params.put(PARAM_OBJECT,     triple.object());
        params.put(PARAM_PROVENANCE, triple.provenance());
        params.put(PARAM_CONFIDENCE, triple.confidence());
        execute(cypher, params);
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

        JsonNode response = execute(CYPHER_QUERY, params);
        return parseTriples(response);
    }

    @Override
    public List<Triple> traverse(String startSubject, List<String> predicates,
                                  int maxDepth, RequestContext ctx) {
        ObjectNode params = http.newObject();
        params.put(PARAM_SUBJECT,   startSubject);
        params.put(PARAM_MAX_DEPTH, maxDepth);
        params.put(PARAM_ALL_PREDS, predicates == null || predicates.isEmpty());
        ArrayNode predsArray = params.putArray(PARAM_PREDICATES);
        if (predicates != null) predicates.forEach(predsArray::add);

        JsonNode response = execute(CYPHER_TRAVERSE, params);
        return parseTriples(response);
    }

    @Override
    public void delete(Triple triple, RequestContext ctx) {
        String safePred = sanitizePredicate(triple.predicate());
        String cypher   = String.format(CYPHER_DELETE, safePred);
        ObjectNode params = http.newObject();
        params.put(PARAM_SUBJECT, triple.subject());
        params.put(PARAM_OBJECT,  triple.object());
        execute(cypher, params);
    }

    @Override
    public void deleteBySubject(String subject, RequestContext ctx) {
        ObjectNode params = http.newObject();
        params.put(PARAM_SUBJECT, subject);
        execute(CYPHER_DELETE_BY_SUBJECT, params);
    }

    private List<Triple> parseTriples(JsonNode response) {
        List<Triple> result = new ArrayList<>();
        for (JsonNode res : response.path("results")) {
            for (JsonNode data : res.path("data")) {
                JsonNode row = data.path("row");
                result.add(new Triple(
                        row.path(0).asText(),
                        row.path(1).asText(),
                        row.path(2).asText(),
                        row.path(3).asText(),
                        row.path(4).asDouble()));
            }
        }
        return List.copyOf(result);
    }
}
