package com.agentframework.tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.agentframework.core.RequestContext;
import com.agentframework.foundation.TaintLabel;
import com.agentframework.memory.*;
import com.agentframework.memory.impl.TieredMemory;
import com.agentframework.rag.*;
import com.agentframework.security.TaintTracker;

import java.time.Instant;
import java.util.*;

public class RagTest {

    // ── Stubs ────────────────────────────────────────────────────────────────

    static class StubVectorStore implements VectorStore {
        private final List<Neighbor>    neighbors;
        private final Map<String,String> payloads;

        StubVectorStore(List<Neighbor> neighbors, Map<String,String> payloads) {
            this.neighbors = neighbors;
            this.payloads  = payloads;
        }

        @Override public List<Neighbor> search(List<Double> q, int k, RequestContext ctx) {
            return neighbors.subList(0, Math.min(k, neighbors.size()));
        }
        @Override public String getPayload(String id, RequestContext ctx) {
            return payloads.get(id);
        }
        @Override public void insert(String id, List<Double> emb, String payload, RequestContext ctx) {}
        @Override public void delete(String id, RequestContext ctx) {}
    }

    static class StubRelStore implements RelationalStore {
        @Override public List<MemoryRecord> bm25Search(String q, int k, RequestContext ctx) { return List.of(); }
        @Override public void insert(MemoryRecord r, RequestContext ctx) {}
        @Override public void update(String id, MemoryRecord r, RequestContext ctx) {}
        @Override public Optional<MemoryRecord> get(String id, RequestContext ctx) { return Optional.empty(); }
        @Override public List<MemoryRecord> queryByMetadata(Map<String,String> f, RequestContext ctx) { return List.of(); }
        @Override public void delete(String id, RequestContext ctx) {}
    }

    static TieredMemory.EmbeddingFunction embedFn() {
        return text -> List.of(0.1, 0.2, 0.3);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    public void testRetrieveDensePassages() {
        List<Neighbor>    neighbors = List.of(new Neighbor("p1", 0.9), new Neighbor("p2", 0.8));
        Map<String,String> payloads = Map.of("p1", "content one", "p2", "content two");

        BasicRagService svc = new BasicRagService(
                new StubVectorStore(neighbors, payloads), new StubRelStore(), embedFn());

        List<Passage> results = svc.retrieve(new RagQuery("what is X", 2));

        assertEquals(2, results.size(),          "topK=2 returned");
        assertEquals("p1",          results.get(0).id(),      "first passage id");
        assertEquals("content one", results.get(0).content(), "passage content");
        assertEquals("dense",       results.get(0).source(),  "source=dense");
    }

    @Test
    public void testRetrieveTopKLimit() {
        List<Neighbor>    neighbors = List.of(
                new Neighbor("a", 0.9), new Neighbor("b", 0.8),
                new Neighbor("c", 0.7), new Neighbor("d", 0.6));
        Map<String,String> payloads = Map.of("a","A","b","B","c","C","d","D");

        BasicRagService svc = new BasicRagService(
                new StubVectorStore(neighbors, payloads), new StubRelStore(), embedFn());

        List<Passage> results = svc.retrieve(new RagQuery("q", 2));
        assertEquals(2, results.size(), "topK respected");
    }

    @Test
    public void testRetrieveLabelsExternalTaint() {
        List<Neighbor>    neighbors = List.of(new Neighbor("p1", 0.9));
        Map<String,String> payloads = Map.of("p1", "sensitive data");
        TaintTracker tracker = new TaintTracker();

        BasicRagService svc = new BasicRagService(
                new StubVectorStore(neighbors, payloads), new StubRelStore(),
                embedFn(), tracker);

        svc.retrieve(new RagQuery("q", 1));

        assertEquals(TaintLabel.EXTERNAL, tracker.labelOf("p1"),
                "retrieved passage must be labelled EXTERNAL");
    }

    @Test
    public void testRetrieveNullPayloadFallsBackToEmpty() {
        List<Neighbor>    neighbors = List.of(new Neighbor("p1", 0.9));
        Map<String,String> payloads = Map.of(); // no payload for p1

        BasicRagService svc = new BasicRagService(
                new StubVectorStore(neighbors, payloads), new StubRelStore(), embedFn());

        List<Passage> results = svc.retrieve(new RagQuery("q", 1));
        assertEquals(1, results.size());
        assertEquals("", results.get(0).content(), "null payload -> empty string");
    }

    @Test
    public void testPassageRecord() {
        Passage p = new Passage("id1", "text", "src", "dense", 0.95, Instant.now(), Map.of());
        assertEquals("id1",  p.id());
        assertEquals("text", p.content());
        assertEquals(0.95,   p.score(), 1e-9);
        assertEquals("dense", p.source());
    }

    @Test
    public void testRagQuery() {
        RagQuery q = new RagQuery("find me X", 5);
        assertEquals("find me X", q.naturalLanguage());
        assertEquals(5, q.topK());
    }
}
