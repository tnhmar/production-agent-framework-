package com.agentframework.memory.impl;
import com.agentframework.core.RequestContext;
import com.agentframework.memory.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
public class TieredMemory implements Memory {
    private final VectorStore      hotVec,  warmVec;
    private final RelationalStore  hotRel,  warmRel, coldRel;
    private final ObjectStore      coldObj;
    private final KnowledgeGraph   kg;
    private final ProspectiveScheduler scheduler;
    private final EntityExtractor  extractor;
    private final ImportanceScorer scorer;
    private final WriteGate        gate;
    private final MemoryAuditLog   auditLog;
    private final EmbeddingFunction embedFn;
    private final Map<String,Instant> expirations = new ConcurrentHashMap<>();

    public interface EmbeddingFunction { List<Double> embed(String text); }

    private TieredMemory(Builder b){
        hotVec=b.hotVec; warmVec=b.warmVec; hotRel=b.hotRel; warmRel=b.warmRel;
        coldRel=b.coldRel; coldObj=b.coldObj; kg=b.kg; scheduler=b.scheduler;
        extractor=b.extractor; scorer=b.scorer; gate=b.gate;
        auditLog=b.auditLog; embedFn=b.embedFn;
        Thread janitor = new Thread(this::janitorLoop, "memory-janitor");
        janitor.setDaemon(true); janitor.start();
    }

    // ── Write ────────────────────────────────────────────────────────────
    public String write(MemoryContent content, MemoryType type, MemoryMetadata meta, RequestContext ctx) {
        if (gate.evaluate(content, meta) == WriteDecision.REJECT) {
            auditLog.record(auditEntry("REJECTED","",ctx,meta), ctx);
            return null;
        }
        // m4 fix: compute importance score at write time via ImportanceScorer
        EvaluationContext evalCtx = EvaluationContext.current();
        String id = UUID.randomUUID().toString();
        // Bootstrap a provisional record to score (accessCount=0, score=meta.importanceScore())
        MemoryRecord provisional = new MemoryRecord(id, content, type, meta, meta.importanceScore(), 0);
        double computedImportance = scorer.score(provisional, evalCtx);
        // Rebuild metadata with the computed importance so downstream retrieval ranking is accurate
        MemoryMetadata scoredMeta = new MemoryMetadata(
            computedImportance, meta.createdAt(), meta.source(), meta.tags());
        MemoryRecord rec = new MemoryRecord(id, content, type, scoredMeta, computedImportance, 0);

        List<Double> emb = content.embedding() != null ? content.embedding() : embedFn.embed(content.text());
        hotVec.insert(id, emb, content.text(), ctx);
        hotRel.insert(rec, ctx);
        if (type == MemoryType.SEMANTIC || type == MemoryType.EPISODIC)
            extractor.extract(content.text(), meta.source()).forEach(t -> kg.upsert(t, ctx));
        auditLog.record(auditEntry("WRITE", id, ctx, scoredMeta), ctx);
        return id;
    }

    // ── Retrieve (hot + warm, dense + sparse, ranked by importance) ──────
    public List<MemoryRecord> retrieve(MemoryQuery query, int topK, RequestContext ctx) {
        List<Double> qEmb = query.embedding() != null ? query.embedding() : embedFn.embed(query.naturalLanguage());
        int over = topK * 2;
        List<Neighbor>      hotDense  = hotVec.search(qEmb, over, ctx);
        List<Neighbor>      warmDense = warmVec.search(qEmb, over, ctx);
        List<MemoryRecord>  hotSparse = hotRel.bm25Search(query.naturalLanguage(), over, ctx);
        List<MemoryRecord>  warmSparse= warmRel.bm25Search(query.naturalLanguage(), over, ctx);

        Map<String,MemoryRecord> cands = new LinkedHashMap<>();
        hotDense .forEach(n -> hotRel.get(n.id(),ctx).ifPresent(r -> cands.put(r.id(), r.withScore(n.score()))));
        warmDense.forEach(n -> warmRel.get(n.id(),ctx).ifPresent(r -> cands.putIfAbsent(r.id(), r.withScore(n.score()))));
        hotSparse .forEach(r -> cands.putIfAbsent(r.id(), r));
        warmSparse.forEach(r -> cands.putIfAbsent(r.id(), r));

        EvaluationContext evalCtx = new EvaluationContext(Instant.now(), null, List.of());
        List<MemoryRecord> ranked = new ArrayList<>(cands.values());
        ranked.sort((a,b) -> Double.compare(
            b.score() * (0.5 + 0.5 * scorer.score(b, evalCtx)),
            a.score() * (0.5 + 0.5 * scorer.score(a, evalCtx))));
        return ranked.stream().filter(r -> r.score() >= query.minScore()).limit(topK)
               .collect(Collectors.toList());
    }

    public void update(String id, MemoryContent content, RequestContext ctx) {
        hotRel.get(id, ctx).ifPresent(rec -> {
            MemoryRecord updated = new MemoryRecord(id, content, rec.type(), rec.meta(), rec.score(), rec.accessCount());
            hotRel.update(id, updated, ctx);
            hotVec.delete(id, ctx);
            hotVec.insert(id, embedFn.embed(content.text()), content.text(), ctx);
        });
    }
    public void delete(String id, RequestContext ctx) {
        hotVec.delete(id,ctx); warmVec.delete(id,ctx);
        hotRel.delete(id,ctx); warmRel.delete(id,ctx); coldRel.delete(id,ctx);
        coldObj.delete(id,ctx);
    }
    public void expire(String id, Duration ttl, RequestContext ctx) {
        if (ttl != null) expirations.put(id, Instant.now().plus(ttl));
    }
    public MemorySummary consolidate(String sessionId, RequestContext ctx) {
        List<MemoryRecord> recs = hotRel.queryByMetadata(Map.of("sessionId", sessionId), ctx);
        if (recs.isEmpty()) return new MemorySummary(sessionId, "", List.of(), Instant.now());
        String combined = recs.stream().map(r -> r.content().text()).collect(Collectors.joining("\n"));
        String summary  = combined.length() > 500 ? combined.substring(0,500)+"..." : combined;
        return new MemorySummary(sessionId, summary, recs.stream().map(MemoryRecord::id).toList(), Instant.now());
    }

    private void janitorLoop() {
        while (!Thread.interrupted()) {
            try { Thread.sleep(30_000); janitorSweep(); } catch (InterruptedException e) { break; }
        }
    }
    private void janitorSweep() {
        Instant now = Instant.now();
        RequestContext sys = RequestContext.system();
        new HashMap<>(expirations).forEach((id, exp) -> {
            if (now.isAfter(exp)) {
                hotRel.get(id, sys).ifPresent(rec -> {
                    coldObj.put(id, rec.content().text().getBytes(), sys);
                    coldRel.insert(rec, sys);
                    delete(id, sys);
                });
                expirations.remove(id);
            }
        });
    }
    private MemoryAuditEntry auditEntry(String op, String id, RequestContext ctx, MemoryMetadata meta) {
        return new MemoryAuditEntry(UUID.randomUUID().toString(), op, id,
            ctx.userId(), "agent", "", Instant.now(), meta.source(), "",
            meta.importanceScore(), op.equals("REJECTED")?"rejected":"accepted",
            Duration.ZERO, meta.tags());
    }

    // ── Builder ──────────────────────────────────────────────────────────
    public static Builder builder() { return new Builder(); }
    public static class Builder {
        VectorStore hotVec, warmVec; RelationalStore hotRel, warmRel, coldRel;
        ObjectStore coldObj; KnowledgeGraph kg; ProspectiveScheduler scheduler;
        EntityExtractor extractor; ImportanceScorer scorer; WriteGate gate;
        MemoryAuditLog auditLog; EmbeddingFunction embedFn;
        public Builder hotVec(VectorStore v){hotVec=v;return this;}
        public Builder warmVec(VectorStore v){warmVec=v;return this;}
        public Builder hotRel(RelationalStore r){hotRel=r;return this;}
        public Builder warmRel(RelationalStore r){warmRel=r;return this;}
        public Builder coldRel(RelationalStore r){coldRel=r;return this;}
        public Builder coldObj(ObjectStore o){coldObj=o;return this;}
        public Builder kg(KnowledgeGraph g){kg=g;return this;}
        public Builder scheduler(ProspectiveScheduler s){scheduler=s;return this;}
        public Builder extractor(EntityExtractor e){extractor=e;return this;}
        public Builder scorer(ImportanceScorer s){scorer=s;return this;}
        public Builder gate(WriteGate g){gate=g;return this;}
        public Builder auditLog(MemoryAuditLog a){auditLog=a;return this;}
        public Builder embedFn(EmbeddingFunction f){embedFn=f;return this;}
        public TieredMemory build() {
            Objects.requireNonNull(hotVec,"hotVec"); Objects.requireNonNull(warmVec,"warmVec");
            Objects.requireNonNull(hotRel,"hotRel"); Objects.requireNonNull(warmRel,"warmRel");
            Objects.requireNonNull(coldRel,"coldRel"); Objects.requireNonNull(coldObj,"coldObj");
            Objects.requireNonNull(kg,"kg"); Objects.requireNonNull(scheduler,"scheduler");
            Objects.requireNonNull(extractor,"extractor"); Objects.requireNonNull(scorer,"scorer");
            Objects.requireNonNull(gate,"gate"); Objects.requireNonNull(auditLog,"auditLog");
            Objects.requireNonNull(embedFn,"embedFn");
            return new TieredMemory(this);
        }
        /** Build with all in-memory implementations (for tests). */
        public static TieredMemory inMemory() {
            InMemoryVectorStore    hot  = new InMemoryVectorStore();
            InMemoryVectorStore    warm = new InMemoryVectorStore();
            InMemoryRelationalStore hr  = new InMemoryRelationalStore();
            InMemoryRelationalStore wr  = new InMemoryRelationalStore();
            InMemoryRelationalStore cr  = new InMemoryRelationalStore();
            InMemoryObjectStore    co   = new InMemoryObjectStore();
            InMemoryKnowledgeGraph  kg  = new InMemoryKnowledgeGraph();
            return new Builder()
                .hotVec(hot).warmVec(warm).hotRel(hr).warmRel(wr).coldRel(cr).coldObj(co)
                .kg(kg).scheduler(new InMemoryProspectiveScheduler())
                .extractor(new PassThroughEntityExtractor())
                .scorer(new DefaultImportanceScorer())
                .gate(PatternWriteGate.defaultSensitiveData())
                .auditLog(new InMemoryAuditLog())
                .embedFn(text -> {
                    // deterministic keyword hash embedding (no external deps)
                    double[] v = new double[16];
                    for (int i=0;i<text.length();i++) v[i%16]+=text.charAt(i);
                    double norm = 1e-8;
                    for (double x:v) norm+=x*x; norm=Math.sqrt(norm);
                    List<Double> out=new ArrayList<>();
                    for (double x:v) out.add(x/norm);
                    return out;
                })
                .build();
        }
    }
}
