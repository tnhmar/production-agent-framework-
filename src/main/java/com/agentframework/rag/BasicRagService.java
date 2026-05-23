package com.agentframework.rag;

import com.agentframework.core.RequestContext;
import com.agentframework.foundation.TaintLabel;
import com.agentframework.memory.*;
import com.agentframework.memory.impl.TieredMemory;
import com.agentframework.security.TaintTracker;
import java.time.Instant;
import java.util.*;

/**
 * Hybrid dense + sparse retrieval service.
 *
 * <p><b>P4 fix</b>: every passage returned by {@link #retrieve} is labelled
 * {@link TaintLabel#EXTERNAL} in the shared {@link TaintTracker}.  When the
 * perception layer injects a passage into working memory it must propagate
 * this label so the taint pipeline can track lineage and block hostile
 * downstream tool calls if the passage content promotes to HOSTILE.
 *
 * <p>A backward-compatible no-arg {@link TaintTracker} constructor is kept
 * so existing call-sites compile without change; the preferred constructor
 * accepts an externally managed tracker (e.g. one shared with
 * {@code SecurityEnforcer} and {@code TaintActionValidator}).
 */
public class BasicRagService implements RagService {

    private final VectorStore                  vectorStore;
    private final RelationalStore              relStore;
    private final TieredMemory.EmbeddingFunction embedFn;
    private final TaintTracker                 taintTracker;

    /** Full constructor — preferred in production; share the tracker with the security layer. */
    public BasicRagService(VectorStore vs, RelationalStore rs,
                            TieredMemory.EmbeddingFunction fn,
                            TaintTracker taintTracker) {
        this.vectorStore  = Objects.requireNonNull(vs, "vectorStore");
        this.relStore     = Objects.requireNonNull(rs, "relStore");
        this.embedFn      = Objects.requireNonNull(fn, "embedFn");
        this.taintTracker = Objects.requireNonNull(taintTracker, "taintTracker");
    }

    /** Backward-compatible constructor — creates an isolated TaintTracker. */
    public BasicRagService(VectorStore vs, RelationalStore rs,
                            TieredMemory.EmbeddingFunction fn) {
        this(vs, rs, fn, new TaintTracker());
    }

    @Override
    public List<Passage> retrieve(RagQuery query) {
        RequestContext ctx = RequestContext.system();
        List<Double>       qEmb   = embedFn.embed(query.naturalLanguage());
        int                over   = query.topK() * 2;
        List<Neighbor>     dense  = vectorStore.search(qEmb, over, ctx);
        List<MemoryRecord> sparse = relStore.bm25Search(query.naturalLanguage(), over, ctx);

        Map<String, Passage> results = new LinkedHashMap<>();
        dense.forEach(n -> {
            String payload = vectorStore.getPayload(n.id(), ctx);
            results.put(n.id(), new Passage(n.id(),
                payload != null ? payload : "", "", "dense",
                n.score(), Instant.now(), Map.of()));
        });
        sparse.forEach(r -> results.putIfAbsent(r.id(),
            new Passage(r.id(), r.content().text(), "", "sparse",
                r.score(), Instant.now(), Map.of())));

        List<Passage> topK = new ArrayList<>(results.values())
            .subList(0, Math.min(query.topK(), results.size()));

        // P4 fix: label every retrieved passage EXTERNAL so the taint pipeline
        // can track lineage when passages are injected into working memory.
        topK.forEach(p -> taintTracker.label(p.id(), TaintLabel.EXTERNAL));

        return topK;
    }
}
