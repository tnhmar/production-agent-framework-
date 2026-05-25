package com.agentframework.integration.store;

import com.agentframework.core.RequestContext;
import com.agentframework.integration.http.*;
import com.agentframework.memory.Neighbor;
import com.agentframework.memory.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * VectorStore adapter for Pinecone Data Plane API.
 *
 * Auth:    {@code Api-Key} header (NOT Bearer)
 * Upsert:  POST /vectors/upsert   — vectors[] wrapper + namespace required
 * Query:   POST /query            — includeMetadata:true required for payload
 * Fetch:   GET  /vectors/fetch    — ids= query param, URL-encoded
 * Delete:  POST /vectors/delete
 */
public final class PineconeVectorStore implements VectorStore {

    private static final String PATH_UPSERT    = "/vectors/upsert";
    private static final String PATH_QUERY     = "/query";
    private static final String PATH_FETCH     = "/vectors/fetch?ids=";
    private static final String PATH_DELETE    = "/vectors/delete";

    private static final String FIELD_VECTORS          = "vectors";
    private static final String FIELD_NAMESPACE        = "namespace";
    private static final String FIELD_ID               = "id";
    private static final String FIELD_VALUES           = "values";
    private static final String FIELD_METADATA         = "metadata";
    private static final String FIELD_PAYLOAD          = "payload";
    private static final String FIELD_TOP_K            = "topK";
    private static final String FIELD_VECTOR           = "vector";
    private static final String FIELD_INCLUDE_META     = "includeMetadata";
    private static final String FIELD_MATCHES          = "matches";
    private static final String FIELD_SCORE            = "score";
    private static final String FIELD_IDS              = "ids";

    private final StoreConfig    config;
    private final JsonHttpClient http;
    private final AuthStrategy   auth;

    public PineconeVectorStore(StoreConfig config) {
        this.config = config;
        this.http   = new JsonHttpClient(config.policy());
        // Pinecone requires "Api-Key" header, not "Authorization: Bearer"
        this.auth   = AuthStrategy.pineconeApiKey(config.apiKey());
    }

    @Override
    public void insert(String id, List<Double> embedding, String payload,
                       RequestContext ctx) {
        ObjectNode body = http.newObject();
        // vectors must be wrapped in an array
        ArrayNode vectors = body.putArray(FIELD_VECTORS);
        ObjectNode vec = vectors.addObject();
        vec.put(FIELD_ID, id);
        ArrayNode vals = vec.putArray(FIELD_VALUES);
        embedding.forEach(vals::add);
        ObjectNode meta = vec.putObject(FIELD_METADATA);
        meta.put(FIELD_PAYLOAD, payload);
        // namespace from config
        body.put(FIELD_NAMESPACE, config.namespace());

        http.post(config.host() + PATH_UPSERT, body, auth);
    }

    @Override
    public List<Neighbor> search(List<Double> query, int k, RequestContext ctx) {
        ObjectNode body = http.newObject();
        body.put(FIELD_TOP_K, k);
        body.put(FIELD_INCLUDE_META, true);   // required — without this payload is null
        body.put(FIELD_NAMESPACE, config.namespace());
        ArrayNode vec = body.putArray(FIELD_VECTOR);
        query.forEach(vec::add);

        JsonNode response = http.post(config.host() + PATH_QUERY, body, auth);
        List<Neighbor> neighbors = new ArrayList<>();
        for (JsonNode match : response.path(FIELD_MATCHES)) {
            String  nid     = match.path(FIELD_ID).asText();
            double  score   = match.path(FIELD_SCORE).asDouble();
            String  text    = match.path(FIELD_METADATA).path(FIELD_PAYLOAD).asText();
            neighbors.add(new Neighbor(nid, score, text));
        }
        return List.copyOf(neighbors);
    }

    @Override
    public void delete(String id, RequestContext ctx) {
        ObjectNode body = http.newObject();
        body.putArray(FIELD_IDS).add(id);
        body.put(FIELD_NAMESPACE, config.namespace());
        http.post(config.host() + PATH_DELETE, body, auth);
    }

    @Override
    public String getPayload(String id, RequestContext ctx) {
        // URL-encode the id to prevent query-string injection
        String url = config.host() + PATH_FETCH
                + URLEncoder.encode(id, StandardCharsets.UTF_8)
                + "&namespace=" + URLEncoder.encode(config.namespace(), StandardCharsets.UTF_8);
        JsonNode response = http.get(url, auth);
        // Correct path: vectors -> {id} -> metadata -> payload
        return response
                .path(FIELD_VECTORS).path(id)
                .path(FIELD_METADATA).path(FIELD_PAYLOAD)
                .asText();
    }
}
