package com.agentframework.integration.store;

import com.agentframework.core.RequestContext;
import com.agentframework.integration.http.*;
import com.agentframework.memory.Neighbor;
import com.agentframework.memory.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * VectorStore adapter for Weaviate REST + GraphQL API.
 *
 * Insert:  PUT  /v1/objects/{class}/{uuid}  (idempotent upsert)
 * Search:  POST /v1/graphql  (nearVector + _additional { distance })
 * Delete:  DELETE /v1/objects/{class}/{uuid}
 * Fetch:   GET  /v1/objects/{class}/{uuid}
 *
 * <h3>C2 fix — app_id round-trip</h3>
 * <p>Root cause: {@code insert()} stored objects under {@code toUuid(originalId)}
 * (a name-based UUID hash of the application id). {@code search()} returned
 * {@code _additional.id} — which is the Weaviate-internal UUID (already the
 * hashed form). When a caller then called {@code getPayload(weaviateUuid)},
 * {@code objectUrl()} called {@code toUuid(weaviateUuid)} — hashing an
 * already-hashed value — producing a completely different UUID.  The fetch
 * returned an empty object; {@code getPayload()} silently returned {@code ""}
 * on every call.
 *
 * <p>Fix: the original application id is now stored in a dedicated
 * {@code app_id} property at insert time.  {@code search()} requests
 * {@code app_id} in the GraphQL projection and reads it back — not
 * {@code _additional.id}.  {@code getPayload(id)} continues to receive the
 * original application id and hashes it exactly once, consistent with
 * {@code insert()}.
 */
public final class WeaviateVectorStore implements VectorStore {

    private static final String PATH_OBJECTS   = "/v1/objects/";
    private static final String PATH_GRAPHQL   = "/v1/graphql";

    private static final String FIELD_CLASS      = "class";
    private static final String FIELD_PROPERTIES = "properties";
    private static final String FIELD_VECTOR     = "vector";
    private static final String FIELD_ID         = "id";
    private static final String FIELD_PAYLOAD    = "payload";
    private static final String FIELD_APP_ID     = "app_id";   // C2 fix: store original id
    private static final String FIELD_QUERY      = "query";
    private static final String FIELD_DATA       = "data";
    private static final String FIELD_GET        = "Get";
    private static final String FIELD_DISTANCE   = "distance";
    private static final String FIELD_ADDITIONAL = "_additional";

    private final StoreConfig    config;
    private final JsonHttpClient http;
    private final AuthStrategy   auth;

    public WeaviateVectorStore(StoreConfig config) {
        this.config = config;
        this.http   = new JsonHttpClient(config.policy());
        this.auth   = AuthStrategy.bearer(config.apiKey());
    }

    private String toUuid(String id) {
        return UUID.nameUUIDFromBytes(
                id.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private String objectUrl(String id) {
        return config.host() + PATH_OBJECTS + config.namespace() + "/" + toUuid(id);
    }

    @Override
    public void insert(String id, List<Double> embedding, String payload,
                       RequestContext ctx) {
        ObjectNode body = http.newObject();
        body.put(FIELD_CLASS, config.namespace());
        body.put(FIELD_ID,    toUuid(id));
        // C2 fix: persist original application id alongside payload so search()
        // can return it without double-hashing.
        ObjectNode props = body.putObject(FIELD_PROPERTIES);
        props.put(FIELD_PAYLOAD, payload);
        props.put(FIELD_APP_ID,  id);
        ArrayNode vec = body.putArray(FIELD_VECTOR);
        embedding.forEach(vec::add);
        http.put(objectUrl(id), body, auth);
    }

    @Override
    public List<Neighbor> search(List<Double> query, int k, RequestContext ctx) {
        ObjectNode tmp = http.newObject();
        ArrayNode vecArr = tmp.putArray("v");
        query.forEach(vecArr::add);
        String vecJson = vecArr.toString();

        String className = config.namespace();
        // C2 fix: request app_id in GraphQL projection; do NOT use _additional.id
        // which is the already-hashed Weaviate-internal UUID.
        String gql = String.format(
                "{ Get { %s(nearVector: { vector: %s } limit: %d) "
              + "{ %s %s %s { distance } } } }",
                className, vecJson, k, FIELD_PAYLOAD, FIELD_APP_ID, FIELD_ADDITIONAL);

        ObjectNode body = http.newObject();
        body.put(FIELD_QUERY, gql);

        JsonNode response = http.post(config.host() + PATH_GRAPHQL, body, auth);
        JsonNode items    = response.path(FIELD_DATA).path(FIELD_GET).path(className);

        List<Neighbor> neighbors = new ArrayList<>();
        for (JsonNode item : items) {
            // C2 fix: read original application id from app_id property
            String appId = item.path(FIELD_APP_ID).asText();
            double score = 1.0 - item.path(FIELD_ADDITIONAL).path(FIELD_DISTANCE).asDouble();
            String text  = item.path(FIELD_PAYLOAD).asText();
            neighbors.add(new Neighbor(appId, score, text));
        }
        return List.copyOf(neighbors);
    }

    @Override
    public void delete(String id, RequestContext ctx) {
        http.delete(objectUrl(id), http.newObject(), auth);
    }

    @Override
    public String getPayload(String id, RequestContext ctx) {
        // getPayload() is unchanged — it always receives the original application
        // id and hashes it exactly once, consistent with insert().
        JsonNode response = http.get(objectUrl(id), auth);
        return response.path(FIELD_PROPERTIES).path(FIELD_PAYLOAD).asText();
    }
}
