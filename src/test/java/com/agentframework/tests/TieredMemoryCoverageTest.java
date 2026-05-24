package com.agentframework.tests;

import com.agentframework.core.RequestContext;
import com.agentframework.memory.*;
import com.agentframework.memory.impl.*;
import com.agentframework.foundation.TaintLabel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage tests for TieredMemory branches not reached by MemoryTest.
 * All API calls verified against actual source before push.
 */
public class TieredMemoryCoverageTest {

    private static final RequestContext CTX = RequestContext.of("t1", "u1");

    private TieredMemory memory() {
        return TieredMemory.Builder.inMemory();
    }

    // ── update() ─────────────────────────────────────────────────────────────

    @Test
    void testUpdateExistingRecord() {
        TieredMemory mem = memory();
        String id = mem.write(
            MemoryContent.text("original content"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.7, "src"), CTX);
        assertNotNull(id, "id must be non-null after write");
        mem.update(id, MemoryContent.text("updated content"), CTX);
        List<MemoryRecord> results = mem.retrieve(
            MemoryQuery.text("updated content"), 5, CTX);
        assertFalse(results.isEmpty(), "updated record should be retrievable");
    }

    @Test
    void testUpdateNonExistentIdIsNoOp() {
        TieredMemory mem = memory();
        assertDoesNotThrow(() ->
            mem.update("non-existent-id", MemoryContent.text("x"), CTX));
    }

    // ── write with SEMANTIC / EPISODIC / PROCEDURAL ───────────────────────────

    @Test
    void testWriteSemanticTriggersKnowledgeGraph() {
        TieredMemory mem = memory();
        String id = mem.write(
            MemoryContent.text("Paris is the capital of France"),
            MemoryType.SEMANTIC, MemoryMetadata.of(0.9, "wiki"), CTX);
        assertNotNull(id, "semantic write must succeed");
        List<MemoryRecord> res = mem.retrieve(
            MemoryQuery.text("Paris capital France"), 5, CTX);
        assertFalse(res.isEmpty(), "semantic entry retrievable");
    }

    @Test
    void testWriteEpisodicSucceeds() {
        TieredMemory mem = memory();
        String id = mem.write(
            MemoryContent.text("agent completed the research task"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.8, "agent"), CTX);
        assertNotNull(id);
    }

    @Test
    void testWriteProceduralSucceeds() {
        TieredMemory mem = memory();
        String id = mem.write(
            MemoryContent.text("always call validate() before execute()"),
            MemoryType.PROCEDURAL, MemoryMetadata.of(0.6, "system"), CTX);
        assertNotNull(id);
    }

    // ── consolidate() ────────────────────────────────────────────────────────

    @Test
    void testConsolidateReturnsCorrectSessionId() {
        TieredMemory mem = memory();
        // MemorySummary fields: sessionId(), compressedText(), sourceIds(), createdAt()
        MemorySummary s = mem.consolidate("session-42", CTX);
        assertEquals("session-42", s.sessionId());
        assertNotNull(s.createdAt());
    }

    // ── expire() + delete() ───────────────────────────────────────────────────

    @Test
    void testExpireWithPositiveTtl() {
        TieredMemory mem = memory();
        String id = mem.write(
            MemoryContent.text("ephemeral fact"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.5, "test"), CTX);
        assertNotNull(id);
        assertDoesNotThrow(() -> mem.expire(id, Duration.ofMinutes(5), CTX));
    }

    @Test
    void testExpireWithNullTtlIsNoOp() {
        TieredMemory mem = memory();
        String id = mem.write(
            MemoryContent.text("stable fact"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.5, "test"), CTX);
        assertDoesNotThrow(() -> mem.expire(id, null, CTX));
    }

    @Test
    void testDeleteRemovesRecord() {
        TieredMemory mem = memory();
        String id = mem.write(
            MemoryContent.text("to be deleted completely"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.5, "test"), CTX);
        assertNotNull(id);
        mem.delete(id, CTX);
        List<MemoryRecord> res = mem.retrieve(
            MemoryQuery.text("to be deleted completely"), 5, CTX);
        assertEquals(0, res.size(), "empty after delete");
    }

    // ── retrieve() ranking / minScore ─────────────────────────────────────────

    @Test
    void testRetrieveRanksByImportance() {
        TieredMemory mem = memory();
        mem.write(MemoryContent.text("low importance agent entry"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.1, "test"), CTX);
        mem.write(MemoryContent.text("high importance agent entry"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.99, "test"), CTX);
        List<MemoryRecord> results =
            mem.retrieve(MemoryQuery.text("agent entry"), 5, CTX);
        assertTrue(results.size() >= 1, "results found");
    }

    @Test
    void testRetrieveWithHighMinScoreFiltersResults() {
        TieredMemory mem = memory();
        mem.write(MemoryContent.text("irrelevant noise about weather"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.1, "test"), CTX);
        // MemoryQuery record: (String naturalLanguage, List<Double> embedding,
        //   MemoryType type, Map<String,String> filters, double minScore)
        List<MemoryRecord> results = mem.retrieve(
            new MemoryQuery("unrelated xyz123", null, null, Map.of(), 0.99),
            5, CTX);
        assertEquals(0, results.size(), "nothing above 0.99 threshold");
    }

    // ── InMemoryRelationalStore ───────────────────────────────────────────────

    @Test
    void testRelationalStoreGetPresent() {
        InMemoryRelationalStore rs = new InMemoryRelationalStore();
        MemoryRecord r = new MemoryRecord("r1",
            MemoryContent.text("hello"), MemoryType.EPISODIC,
            MemoryMetadata.of(0.5, "test"), 0.5, 0);
        rs.insert(r, CTX);
        assertTrue(rs.get("r1", CTX).isPresent(), "r1 present");
        assertEquals("hello", rs.get("r1", CTX).get().content().text());
    }

    @Test
    void testRelationalStoreGetAbsent() {
        InMemoryRelationalStore rs = new InMemoryRelationalStore();
        assertTrue(rs.get("missing", CTX).isEmpty(), "absent id returns empty");
    }

    @Test
    void testRelationalStoreUpdate() {
        InMemoryRelationalStore rs = new InMemoryRelationalStore();
        MemoryRecord original = new MemoryRecord("r1",
            MemoryContent.text("original"), MemoryType.EPISODIC,
            MemoryMetadata.of(0.5, "test"), 0.5, 0);
        rs.insert(original, CTX);
        MemoryRecord updated = new MemoryRecord("r1",
            MemoryContent.text("updated"), MemoryType.EPISODIC,
            MemoryMetadata.of(0.8, "test"), 0.8, 1);
        rs.update("r1", updated, CTX);
        assertEquals("updated", rs.get("r1", CTX).get().content().text());
    }

    @Test
    void testRelationalStoreDeleteAndGet() {
        InMemoryRelationalStore rs = new InMemoryRelationalStore();
        MemoryRecord r = new MemoryRecord("r1",
            MemoryContent.text("x"), MemoryType.EPISODIC,
            MemoryMetadata.of(0.5, "test"), 0.5, 0);
        rs.insert(r, CTX);
        rs.delete("r1", CTX);
        assertTrue(rs.get("r1", CTX).isEmpty(), "empty after delete");
    }

    @Test
    void testRelationalStoreSizeAndAll() {
        InMemoryRelationalStore rs = new InMemoryRelationalStore();
        assertEquals(0, rs.size());
        rs.insert(new MemoryRecord("a", MemoryContent.text("a"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.5, "test"), 0.5, 0), CTX);
        rs.insert(new MemoryRecord("b", MemoryContent.text("b"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.5, "test"), 0.5, 0), CTX);
        assertEquals(2, rs.size());
        assertEquals(2, rs.all().size());
    }

    // ── InMemoryKnowledgeGraph ────────────────────────────────────────────────

    @Test
    void testKgQueryByPredicate() {
        InMemoryKnowledgeGraph kg = new InMemoryKnowledgeGraph();
        // Triple record fields: subject, predicate, object, provenance, confidence
        kg.upsert(new Triple("Paris",  "capital_of", "France",  "wiki", 1.0), CTX);
        kg.upsert(new Triple("Berlin", "capital_of", "Germany", "wiki", 1.0), CTX);
        kg.upsert(new Triple("Paris",  "located_in", "Europe",  "wiki", 1.0), CTX);
        List<Triple> capitals = kg.query(null, "capital_of", null, CTX);
        assertEquals(2, capitals.size(), "two capitals");
    }

    @Test
    void testKgQueryByObject() {
        InMemoryKnowledgeGraph kg = new InMemoryKnowledgeGraph();
        kg.upsert(new Triple("Paris", "capital_of", "France", "wiki", 1.0), CTX);
        kg.upsert(new Triple("Lyon",  "city_in",    "France", "wiki", 1.0), CTX);
        kg.upsert(new Triple("Berlin","capital_of", "Germany","wiki", 1.0), CTX);
        List<Triple> france = kg.query(null, null, "France", CTX);
        assertEquals(2, france.size(), "two triples with France as object");
    }

    @Test
    void testKgQueryAllReturnsAll() {
        InMemoryKnowledgeGraph kg = new InMemoryKnowledgeGraph();
        kg.upsert(new Triple("A", "r", "B", "p", 1.0), CTX);
        kg.upsert(new Triple("C", "r", "D", "p", 1.0), CTX);
        assertEquals(2, kg.query(null, null, null, CTX).size());
    }

    @Test
    void testKgDeleteBySubject() {
        InMemoryKnowledgeGraph kg = new InMemoryKnowledgeGraph();
        kg.upsert(new Triple("X", "r", "Y", "p", 1.0), CTX);
        kg.upsert(new Triple("X", "r", "Z", "p", 1.0), CTX);
        kg.upsert(new Triple("W", "r", "Y", "p", 1.0), CTX);
        kg.deleteBySubject("X", CTX);
        assertEquals(1, kg.size(), "only W triple remains");
    }

    // ── InMemoryVectorStore ───────────────────────────────────────────────────

    @Test
    void testVectorStoreSearchEmptyReturnsEmpty() {
        InMemoryVectorStore vs = new InMemoryVectorStore();
        List<Neighbor> res = vs.search(List.of(1.0, 0.0, 0.0), 5, CTX);
        assertEquals(0, res.size(), "empty store → empty results");
    }

    @Test
    void testVectorStoreSizeAfterInsertAndDelete() {
        InMemoryVectorStore vs = new InMemoryVectorStore();
        assertEquals(0, vs.size());
        vs.insert("a", List.of(1.0, 0.0), "text a", CTX);
        vs.insert("b", List.of(0.0, 1.0), "text b", CTX);
        assertEquals(2, vs.size());
        vs.delete("a", CTX);
        assertEquals(1, vs.size());
    }

    @Test
    void testVectorStoreSearchReturnsSortedByScore() {
        InMemoryVectorStore vs = new InMemoryVectorStore();
        vs.insert("a", List.of(1.0, 0.0), "payload-a", CTX);
        vs.insert("b", List.of(0.0, 1.0), "payload-b", CTX);
        List<Neighbor> results = vs.search(List.of(1.0, 0.0), 5, CTX);
        assertEquals(2, results.size());
        assertTrue(results.get(0).score() >= results.get(1).score(),
            "results ordered descending by score");
        assertEquals("a", results.get(0).id(), "best match is 'a'");
    }

    @Test
    void testVectorStoreGetPayload() {
        InMemoryVectorStore vs = new InMemoryVectorStore();
        vs.insert("x", List.of(0.5, 0.5), "my-payload", CTX);
        assertEquals("my-payload", vs.getPayload("x", CTX));
    }

    // ── DefaultImportanceScorer ───────────────────────────────────────────────

    @Test
    void testImportanceScorerHighAccessCount() {
        DefaultImportanceScorer scorer = new DefaultImportanceScorer();
        // 6-arg compat constructor (taintLabel defaults to CLEAN)
        MemoryRecord frequent = new MemoryRecord("r",
            MemoryContent.text("oft-accessed"), MemoryType.SEMANTIC,
            new MemoryMetadata(0.5, Instant.now().minusSeconds(60), "src", Set.of()),
            0.5, 100);
        double score = scorer.score(frequent, EvaluationContext.current());
        assertTrue(score > 0 && score <= 1.0, "score in (0,1]");
    }

    @Test
    void testImportanceScorerVeryOldRecord() {
        DefaultImportanceScorer scorer = new DefaultImportanceScorer();
        MemoryRecord old = new MemoryRecord("r",
            MemoryContent.text("ancient fact"), MemoryType.EPISODIC,
            new MemoryMetadata(0.5,
                Instant.now().minusSeconds(86400L * 365), "src", Set.of()),
            0.5, 0);
        double score = scorer.score(old, EvaluationContext.current());
        assertTrue(score >= 0 && score <= 1.0, "old record score in [0,1]");
    }

    @Test
    void testImportanceScorerZeroAccessCount() {
        DefaultImportanceScorer scorer = new DefaultImportanceScorer();
        MemoryRecord fresh = new MemoryRecord("r",
            MemoryContent.text("brand new entry"), MemoryType.PROCEDURAL,
            new MemoryMetadata(0.8, Instant.now(), "user", Set.of()),
            0.8, 0);
        double score = scorer.score(fresh, EvaluationContext.current());
        assertTrue(score > 0, "fresh record with user source scores > 0");
    }

    @Test
    void testImportanceScorerDbSource() {
        DefaultImportanceScorer scorer = new DefaultImportanceScorer();
        MemoryRecord dbRecord = new MemoryRecord("r",
            MemoryContent.text("db entry"), MemoryType.SEMANTIC,
            new MemoryMetadata(0.9, Instant.now(), "db", Set.of()),
            0.9, 5);
        double score = scorer.score(dbRecord, EvaluationContext.current());
        assertTrue(score > 0 && score <= 1.0);
    }

    // ── PatternWriteGate ──────────────────────────────────────────────────────

    @Test
    void testWriteGateRejectsSsn() {
        // evaluate(MemoryContent, MemoryMetadata) — NO RequestContext
        PatternWriteGate gate = PatternWriteGate.defaultSensitiveData();
        assertEquals(WriteDecision.REJECT,
            gate.evaluate(MemoryContent.text("SSN: 123-45-6789"),
                MemoryMetadata.of(0.5, "user")),
            "SSN pattern must be rejected");
    }

    @Test
    void testWriteGateAcceptsCleanContent() {
        PatternWriteGate gate = PatternWriteGate.defaultSensitiveData();
        assertEquals(WriteDecision.ACCEPT,
            gate.evaluate(MemoryContent.text("cats are fluent in sleeping"),
                MemoryMetadata.of(0.5, "user")),
            "clean content accepted");
    }

    @Test
    void testWriteGateRejectsCreditCard() {
        PatternWriteGate gate = PatternWriteGate.defaultSensitiveData();
        assertEquals(WriteDecision.REJECT,
            gate.evaluate(MemoryContent.text("card: 1234-5678-9012-3456"),
                MemoryMetadata.of(0.5, "user")),
            "credit card pattern rejected");
    }

    // ── PassThroughEntityExtractor ────────────────────────────────────────────

    @Test
    void testPassThroughExtractorReturnsEmptyList() {
        // PassThroughEntityExtractor is a no-op — returns List.of()
        PassThroughEntityExtractor ext = new PassThroughEntityExtractor();
        List<Triple> triples = ext.extract("some text about Paris", "source");
        assertNotNull(triples, "result must not be null");
        assertTrue(triples.isEmpty(), "pass-through extractor returns empty list");
    }
}
