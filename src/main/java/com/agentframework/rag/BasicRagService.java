package com.agentframework.rag;
import com.agentframework.core.RequestContext;
import com.agentframework.memory.*;
import com.agentframework.memory.impl.TieredMemory;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
public class BasicRagService implements RagService {
    private final VectorStore     vectorStore;
    private final RelationalStore relStore;
    private final TieredMemory.EmbeddingFunction embedFn;

    public BasicRagService(VectorStore vs, RelationalStore rs, TieredMemory.EmbeddingFunction fn) {
        vectorStore=vs; relStore=rs; embedFn=fn;
    }
    public List<Passage> retrieve(RagQuery query) {
        RequestContext ctx = RequestContext.system();
        List<Double> qEmb = embedFn.embed(query.naturalLanguage());
        int over = query.topK() * 2;
        List<Neighbor> dense = vectorStore.search(qEmb, over, ctx);
        List<MemoryRecord> sparse = relStore.bm25Search(query.naturalLanguage(), over, ctx);
        Map<String,Passage> results = new LinkedHashMap<>();
        dense.forEach(n -> {
            String payload = vectorStore.getPayload(n.id(), ctx);
            results.put(n.id(), new Passage(n.id(), payload!=null?payload:"", "", "dense", n.score(), Instant.now(), Map.of()));
        });
        sparse.forEach(r -> results.putIfAbsent(r.id(),
            new Passage(r.id(), r.content().text(), "", "sparse", r.score(), Instant.now(), Map.of())));
        return new ArrayList<>(results.values()).subList(0, Math.min(query.topK(), results.size()));
    }
}
