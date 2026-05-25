package com.agentframework.memory;

import com.agentframework.core.RequestContext;
import com.agentframework.integration.http.StoreConfig;
import com.agentframework.integration.store.*;
import java.util.List;
import java.util.Objects;

/**
 * Port for dense vector storage and ANN search.
 *
 * <pre>{@code
 * VectorStore vs = VectorStore.pinecone(config);
 * VectorStore vs = VectorStore.weaviate(config);
 * }</pre>
 */
public interface VectorStore {

    void insert(String id, List<Double> embedding, String payload, RequestContext ctx);

    List<Neighbor> search(List<Double> query, int k, RequestContext ctx);

    void delete(String id, RequestContext ctx);

    String getPayload(String id, RequestContext ctx);

    static VectorStore pinecone(StoreConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.apiKey().isBlank())
            throw new IllegalArgumentException("Pinecone requires a non-blank apiKey");
        return new PineconeVectorStore(config);
    }

    static VectorStore weaviate(StoreConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.apiKey().isBlank())
            throw new IllegalArgumentException("Weaviate requires a non-blank apiKey");
        return new WeaviateVectorStore(config);
    }
}
