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
 * Insert:  PUT  /v1/objects/{class}/{uuid}  (idempotent upsert semantics)
 * Search:  POST /v1/graphql                 (nearVector, _additional{id distance})
 * Delete:  DELETE /v1/objects/{class}/{uuid}
 * Fetch:   GET  /v1/objects/{class}/{uuid}
 *
 * The Weaviate class name is taken from config.namespace().
 * Caller-supplied string IDs are deterministically converted to UUID v5.
 */
public final class WeaviateVectorStore implements VectorStore {

    private static final String PATH_OBJECTS  = "/v1/objects/";
    private static final String PATH_GRAPHQL  = "/v1/graphql";

    private static final String FIELD_CLASS       = "class";
    private static final String FIELD_PROPERTIES  = "properties";
    private static final String FIELD_VECTOR      = "vector";
    private static final String FIELD_ID          = "id";
    private static final String FIELD_PAYLOAD     = "payload";
    private static final String FIELD_QUERY       = "query";
    private static final String FIELD_DATA        = "data";
    private static final String FIELD_GET         = "Get";
    private static final String FIELD_DISTANCE    = "distance";
    private static final String FIELD_ADDITIONAL  = "_additional";

    private final StoreConfig    config;
    private final JsonHttpClient http;
    private final AuthStrategy   auth;

    public WeaviateVectorStore(StoreConfig config) {
        this.config = config;
        this.http   = new JsonHttpClient(config.policy());
        this.auth   = AuthStrategy.bearer(config.apiKey());
    }

    /** Deterministically converts any string ID to a UUID v3 for Weaviate. */
    private String toUuid(String id) {
        return UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                   .toString();
    }

    private String objectUrl(String id) {
        return config.host() + PATH_OBJECTS
                + config.namespace() + "/" + toUuid(id);
    }

    /**
     * Upsert via PUT /v1/objects/{class}/{uuid}.
     * PUT is idempotent — satisfies the VectorStore.insert contract.
     */
    @Override
    public void insert(String id, List<Double> embedding, String payload,
                       RequestContext ctx) {
        ObjectNode body = http.newObject();
        body.put(FIELD_CLASS, config.namespace());   // required by Weaviate
        body.put(FIELD_ID,    toUuid(id));
        ObjectNode props = body.putObject(FIELD_PROPERTIES);
        props.put(FIELD_PAYLOAD, payload);
        ArrayNode vec = body.putArray(FIELD_VECTOR);
        embedding.forEach(vec::add);

        http.put(objectUrl(id), body, auth);
    }

    /**
     * ANN search via GraphQL nearVector.
     * _additional { id distance } is mandatory to get IDs and scores back.
     */
    @Override
    public List<Neighbor> search(List<Double> query, int k, RequestContext ctx) {
        // Build vector as JSON array using Jackson — never List.toString()
        ObjectNode tempNode = http.newObject();
        ArrayNode vecArr = tempNode.putArray("v");
        query.forEach(vecArr::add);
        String vecJson = vecArr.toString();

        String className = config.namespace();
        String gql = String.format(
                "{ Get { %s(nearVector: { vector: %s } limit: %d) "
                        + "{ %s %s { id distance } } } }",
                className, vecJson, k, FIELD_PAYLOAD, FIELD_ADDITIONAL);

        ObjectNode body = http.newObject();
        body.put(FIELD_QUERY, gql);

        JsonNode response  = http.post(config.host() + PATH_GRAPHQL, body, auth);
        JsonNode items     = response.path(FIELD_DATA).path(FIELD_GET).path(className);

        List<Neighbor> neighbors = new ArrayList<>();
        for (JsonNode item : items) {
            String nid   = item.path(FIELD_ADDITIONAL).path(FIELD_ID).asText();
            double score = 1.0 - item.path(FIELD_ADDITIONAL).path(FIELD_DISTANCE).asDouble();
            String text  = item.path(FIELD_PAYLOAD).asText();
            neighbors.add(new Neighbor(nid, score, text));
        }
        return List.copyOf(neighbors);
    }

    /**
     * Delete via DELETE /v1/objects/{class}/{uuid}.
     * The previous GraphQL mutation approach was incorrect — Weaviate has no
     * GraphQL delete mutation in its schema.
     */
    @Override
    public void delete(String id, RequestContext ctx) {
        http.delete(objectUrl(id), http.newObject(), auth);
    }

    @Override
    public String getPayload(String id, RequestContext ctx) {
        JsonNode response = http.get(objectUrl(id), auth);
        return response.path(FIELD_PROPERTIES).path(FIELD_PAYLOAD).asText();
    }
}
