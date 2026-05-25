package com.agentframework.rag;

import com.agentframework.core.RequestContext;
import com.agentframework.foundation.TaintLabel;
import com.agentframework.memory.*;
import com.agentframework.memory.impl.TieredMemory;
import com.agentframework.security.TaintTracker;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

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
 *
 * <p><b>Score normalisation</b>: dense cosine scores and BM25 scores live on
 * different scales.  Before merging, each list is min-max normalised to
 * [0, 1] independently so the combined ranking is fair.  Results are then
 * sorted by descending normalised score before the topK truncation.
 */
public class BasicRagService implements RagService {

    private static final Logger LOG = Logger.getLogger(BasicRagService.class.getName());

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
        RequestContext ctx  = RequestContext.system();
        List<Double>   qEmb = embedFn.embed(query.naturalLanguage());
        int            over = query.topK() * 2;

        List<Neighbor>     dense  = vectorStore.search(qEmb, over, ctx);
        List<MemoryRecord> sparse = relStore.bm25Search(query.naturalLanguage(), over, ctx);

        // Capture a single timestamp for the entire retrieval event.
        Instant retrievedAt = Instant.now();

        // Min-max normalise dense scores to [0,1].
        double denseMin = dense.stream().mapToDouble(Neighbor::score).min().orElse(0.0);
        double denseMax = dense.stream().mapToDouble(Neighbor::score).max().orElse(1.0);
        double denseRange = denseMax - denseMin > 0 ? denseMax - denseMin : 1.0;

        // Min-max normalise sparse scores to [0,1].
        double sparseMin = sparse.stream().mapToDouble(MemoryRecord::score).min().orElse(0.0);
        double sparseMax = sparse.stream().mapToDouble(MemoryRecord::score).max().orElse(1.0);
        double sparseRange = sparseMax - sparseMin > 0 ? sparseMax - sparseMin : 1.0;

        Map<String, Passage> results = new LinkedHashMap<>();

        dense.forEach(n -> {
            String payload = vectorStore.getPayload(n.id(), ctx);
            if (payload == null || payload.isBlank()) {
                LOG.warning("RAG: null/blank payload for dense result id=" + n.id() + "; skipping.");
                return; // skip — an empty passage would poison the context window
            }
            double normScore = (n.score() - denseMin) / denseRange;
            results.put(n.id(), new Passage(n.id(), payload, "", "dense",
                normScore, retrievedAt, Map.of()));
        });

        sparse.forEach(r -> {
            double normScore = (r.score() - sparseMin) / sparseRange;
            results.putIfAbsent(r.id(), new Passage(r.id(), r.content().text(), "", "sparse",
                normScore, retrievedAt, Map.of()));
        });

        // Sort by descending normalised score before truncation.
        List<Passage> sorted = new ArrayList<>(results.values());
        sorted.sort(Comparator.comparingDouble(Passage::score).reversed());
        List<Passage> topK = sorted.subList(0, Math.min(query.topK(), sorted.size()));

        // P4 fix: label every retrieved passage EXTERNAL so the taint pipeline
        // can track lineage when passages are injected into working memory.
        topK.forEach(p -> taintTracker.label(p.id(), TaintLabel.EXTERNAL));

        return topK;
    }
}
