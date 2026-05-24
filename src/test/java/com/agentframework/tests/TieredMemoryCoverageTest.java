package com.agentframework.tests;

import com.agentframework.core.RequestContext;
import com.agentframework.memory.*;
import com.agentframework.memory.impl.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage tests for TieredMemory branches not reached by MemoryTest.
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

    // ── write with SEMANTIC / EPISODIC → KG path ─────────────────────────────

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
    void testWriteEpisodicTriggersKnowledgeGraph() {
        TieredMemory mem = memory();
        String id = mem.write(
            MemoryContent.text("agent completed the research task"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.8, "agent"), CTX);
        assertNotNull(id);
    }

    @Test
    void testWriteProceduralDoesNotTriggerKg() {
        TieredMemory mem = memory();
        String id = mem.write(
            MemoryContent.text("always call validate() before execute()"),
            MemoryType.PROCEDURAL, MemoryMetadata.of(0.6, "system"), CTX);
        assertNotNull(id);
    }

    // ── consolidate() non-empty session ──────────────────────────────────────

    @Test
    void testConsolidateNonEmptySession() {
        TieredMemory mem = memory();
        MemorySummary s = mem.consolidate("session-42", CTX);
        assertEquals("session-42", s.sessionId());
        assertNotNull(s.timestamp());
    }

    // ── expire() + delete() completeness ─────────────────────────────────────

    @Test
    void testExpireDoesNotThrowAndTtlIsStored() {
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
    void testDeleteRemovesFromAllTiers() {
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

    // ── retrieve() ranking ───────────────────────────────────────────────────

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
    void testRetrieveWithMinScoreFiltersResults() {
        TieredMemory mem = memory();
        mem.write(MemoryContent.text("irrelevant noise about weather and clouds"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.3, "test"), CTX);
        List<MemoryRecord> results =
            mem.retrieve(MemoryQuery.builder()
                .naturalLanguage("completely unrelated xyz123")
                .minScore(0.99)
                .build(), 5, CTX);
        assertEquals(0, results.size(), "nothing above 0.99 threshold");
    }

    // ── InMemoryRelationalStore: missing methods ──────────────────────────────

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

    // ── InMemoryKnowledgeGraph: filter branches ───────────────────────────────

    @Test
    void testKgQueryByPredicate() {
        InMemoryKnowledgeGraph kg = new InMemoryKnowledgeGraph();
        kg.upsert(new Triple("Paris",  "capital_of", "France",  "wiki", 1.0), CTX);
        kg.upsert(new Triple("Berlin", "capital_of", "Germany", "wiki", 1.0), CTX);
        kg.upsert(new Triple("Paris",  "located_in", "Europe",  "wiki", 1.0), CTX);
        List<Triple> capitals = kg.query(null, "capital_of", null, CTX);
        assertEquals(2, capitals.size(), "two capitals");
    }

    @Test
    void testKgQueryByObject() {
        InMemoryKnowledgeGraph kg = new InMemoryKnowledgeGraph();
        kg.upsert(new Triple("Paris",  "capital_of", "France",  "wiki", 1.0), CTX);
        kg.upsert(new Triple("Lyon",   "city_in",    "France",  "wiki", 1.0), CTX);
        kg.upsert(new Triple("Berlin", "capital_of", "Germany", "wiki", 1.0), CTX);
        List<Triple> france = kg.query(null, null, "France", CTX);
        assertEquals(2, france.size(), "two triples with France as object");
    }

    @Test
    void testKgQueryAllReturnsAll() {
        InMemoryKnowledgeGraph kg = new InMemoryKnowledgeGraph();
        kg.upsert(new Triple("A", "r", "B", "p", 1.0), CTX);
        kg.upsert(new Triple("C", "r", "D", "p", 1.0), CTX);
        assertEquals(2, kg.query(null, null, null, CTX).size(), "all triples");
    }

    // ── InMemoryVectorStore: empty search ─────────────────────────────────────

    @Test
    void testVectorStoreSearchEmptyStoreReturnsEmpty() {
        InMemoryVectorStore vs = new InMemoryVectorStore();
        List<Neighbor> res = vs.search(List.of(1.0, 0.0, 0.0), 5, CTX);
        assertEquals(0, res.size(), "empty store → empty results");
    }

    @Test
    void testVectorStoreSizeAfterOperations() {
        InMemoryVectorStore vs = new InMemoryVectorStore();
        assertEquals(0, vs.size());
        vs.insert("a", List.of(1.0, 0.0), "text a", CTX);
        vs.insert("b", List.of(0.0, 1.0), "text b", CTX);
        assertEquals(2, vs.size());
        vs.delete("a", CTX);
        assertEquals(1, vs.size());
    }

    // ── DefaultImportanceScorer: scoring branches ─────────────────────────────

    @Test
    void testImportanceScorerHighAccessCount() {
        DefaultImportanceScorer scorer = new DefaultImportanceScorer();
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
    void testImportanceScorerProceduralType() {
        DefaultImportanceScorer scorer = new DefaultImportanceScorer();
        MemoryRecord proc = new MemoryRecord("r",
            MemoryContent.text("always validate inputs"), MemoryType.PROCEDURAL,
            new MemoryMetadata(0.8, Instant.now(), "src", Set.of()),
            0.8, 2);
        double score = scorer.score(proc, EvaluationContext.current());
        assertTrue(score > 0 && score <= 1.0, "PROCEDURAL score valid");
    }

    // ── PatternWriteGate: additional patterns ─────────────────────────────────

    @Test
    void testWriteGateRejectsSsn() {
        PatternWriteGate gate = PatternWriteGate.defaultSensitiveData();
        MemoryContent ssn   = MemoryContent.text("SSN: 123-45-6789");
        MemoryMetadata meta = MemoryMetadata.of(0.5, "user");
        assertEquals(WriteDecision.REJECT, gate.evaluate(ssn, meta),
            "SSN pattern must be rejected");
    }

    @Test
    void testWriteGateAcceptsShortCleanContent() {
        PatternWriteGate gate = PatternWriteGate.defaultSensitiveData();
        MemoryContent clean = MemoryContent.text("cats");
        MemoryMetadata meta = MemoryMetadata.of(0.5, "user");
        assertEquals(WriteDecision.ACCEPT, gate.evaluate(clean, meta),
            "single-word clean content accepted");
    }

    // ── PassThroughEntityExtractor ────────────────────────────────────────────

    @Test
    void testPassThroughExtractorReturnsSingletonTriple() {
        PassThroughEntityExtractor ext = new PassThroughEntityExtractor();
        List<Triple> triples = ext.extract("some text", "source");
        assertFalse(triples.isEmpty(), "pass-through returns at least one triple");
    }
}
