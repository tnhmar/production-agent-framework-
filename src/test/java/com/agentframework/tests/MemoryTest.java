package com.agentframework.tests;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.agentframework.core.RequestContext;
import com.agentframework.memory.*;
import com.agentframework.memory.impl.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
public class MemoryTest {

    private static final RequestContext CTX = RequestContext.of("tenant1","user1");

    @Test
    public void testInMemoryVectorStoreInsertSearch() {
        InMemoryVectorStore vs = new InMemoryVectorStore();
        vs.insert("id1", List.of(1.0,0.0,0.0), "payload1", CTX);
        vs.insert("id2", List.of(0.0,1.0,0.0), "payload2", CTX);
        List<Neighbor> res = vs.search(List.of(1.0,0.0,0.0), 1, CTX);
        assertEquals(1, res.size(), "1 result");
        assertEquals("id1", res.get(0).id(), "nearest is id1");
    }

    @Test
    public void testInMemoryVectorStoreDelete() {
        InMemoryVectorStore vs = new InMemoryVectorStore();
        vs.insert("id1", List.of(1.0,0.0), "payload1", CTX);
        vs.delete("id1", CTX);
        assertEquals(0, vs.size(), "empty after delete");
    }

    @Test
    public void testInMemoryRelationalStoreBM25() {
        InMemoryRelationalStore rs = new InMemoryRelationalStore();
        MemoryRecord r1 = new MemoryRecord("r1",
            MemoryContent.text("the quick brown fox"), MemoryType.EPISODIC,
            MemoryMetadata.of(0.8,"test"), 0.8, 0);
        MemoryRecord r2 = new MemoryRecord("r2",
            MemoryContent.text("lazy dog sleeping"), MemoryType.EPISODIC,
            MemoryMetadata.of(0.5,"test"), 0.5, 0);
        rs.insert(r1, CTX); rs.insert(r2, CTX);
        List<MemoryRecord> res = rs.bm25Search("fox", 5, CTX);
        assertEquals(1, res.size(), "1 BM25 hit");
        assertEquals("r1", res.get(0).id(), "r1 matches fox");
    }

    @Test
    public void testInMemoryKnowledgeGraphUpsertQuery() {
        InMemoryKnowledgeGraph kg = new InMemoryKnowledgeGraph();
        Triple t = new Triple("Paris","capital_of","France","wiki",1.0);
        kg.upsert(t, CTX);
        List<Triple> q = kg.query("Paris", null, null, CTX);
        assertEquals(1, q.size(), "1 triple");
        assertEquals("France", q.get(0).object(), "France");
    }

    @Test
    public void testInMemoryKnowledgeGraphDeleteBySubject() {
        InMemoryKnowledgeGraph kg = new InMemoryKnowledgeGraph();
        kg.upsert(new Triple("A","rel","B","p",1.0), CTX);
        kg.upsert(new Triple("A","rel","C","p",1.0), CTX);
        kg.deleteBySubject("A", CTX);
        assertEquals(0, kg.size(), "all deleted");
    }

    @Test
    public void testPatternWriteGateRejectsSensitiveData() {
        PatternWriteGate gate = PatternWriteGate.defaultSensitiveData();
        MemoryContent credit = MemoryContent.text("card: 1234-5678-9012-3456");
        MemoryContent clean  = MemoryContent.text("user likes cats");
        MemoryMetadata meta  = MemoryMetadata.of(0.5,"user");
        assertEquals(WriteDecision.REJECT, gate.evaluate(credit, meta), "credit card rejected");
        assertEquals(WriteDecision.ACCEPT, gate.evaluate(clean, meta), "clean accepted");
    }

    @Test
    public void testImportanceScorer() {
        DefaultImportanceScorer scorer = new DefaultImportanceScorer();
        MemoryRecord rec = new MemoryRecord("r1",
            MemoryContent.text("important fact"),
            MemoryType.SEMANTIC,
            new MemoryMetadata(0.9, java.time.Instant.now().minusSeconds(3600), "user", Set.of()),
            0.9, 5);
        double score = scorer.score(rec, EvaluationContext.current());
        assertTrue(score > 0 && score <= 1.0, "score in (0,1]");
    }

    @Test
    public void testTieredMemoryInMemoryWrite() {
        TieredMemory mem = TieredMemory.Builder.inMemory();
        String id = mem.write(MemoryContent.text("test memory entry"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.8,"test"), CTX);
        assertNotNull(id, "id returned");
    }

    @Test
    public void testTieredMemoryRetrieve() {
        TieredMemory mem = TieredMemory.Builder.inMemory();
        mem.write(MemoryContent.text("the agent successfully completed the task"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.9,"test"), CTX);
        mem.write(MemoryContent.text("unrelated entry about weather"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.5,"test"), CTX);
        List<MemoryRecord> results = mem.retrieve(MemoryQuery.text("agent task"), 5, CTX);
        assertTrue(results.size() >= 1, "at least 1 result");
    }

    @Test
    public void testTieredMemoryRejectsSensitiveData() {
        TieredMemory mem = TieredMemory.Builder.inMemory();
        String id = mem.write(
            MemoryContent.text("my card is 1234-5678-9012-3456"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.5,"user"), CTX);
        assertNull(id, "sensitive data rejected, id null");
    }

    @Test
    public void testTieredMemoryDelete() {
        TieredMemory mem = TieredMemory.Builder.inMemory();
        String id = mem.write(MemoryContent.text("to delete"),
            MemoryType.EPISODIC, MemoryMetadata.of(0.5,"test"), CTX);
        assertNotNull(id, "written");
        mem.delete(id, CTX);
        // after delete retrieve returns empty
        List<MemoryRecord> res = mem.retrieve(MemoryQuery.text("to delete"), 5, CTX);
        assertEquals(0, res.size(), "empty after delete");
    }

    @Test
    public void testTieredMemoryConsolidate() {
        TieredMemory mem = TieredMemory.Builder.inMemory();
        // consolidate empty session returns empty summary
        MemorySummary s = mem.consolidate("session-x", CTX);
        assertEquals("session-x", s.sessionId(), "sessionId");
        assertEquals("", s.compressedText(), "empty text");
    }

    @Test
    public void testAuditLogRecords() {
        InMemoryAuditLog log = new InMemoryAuditLog();
        MemoryAuditEntry e = new MemoryAuditEntry("a1","WRITE","r1","u1","agent","s1",
            java.time.Instant.now(),"test","hash",0.8,"full",Duration.ZERO,Set.of());
        log.record(e, CTX);
        assertEquals(1, log.size(), "1 audit entry");
        assertEquals(1, log.historyOf("r1").size(), "history of r1");
    }

    @Test
    public void testProspectiveScheduler() {
        InMemoryProspectiveScheduler sched = new InMemoryProspectiveScheduler();
        ProspectiveRecord rec = new ProspectiveRecord("pr1","u1","remind about meeting",
            new Trigger.SessionStart(), java.time.Instant.now(), false, null);
        sched.schedule(rec);
        assertEquals(1, sched.pendingForUser("u1").size(), "1 pending");
        sched.cancel("pr1");
        assertEquals(0, sched.pendingForUser("u1").size(), "0 after cancel");
    }
}
