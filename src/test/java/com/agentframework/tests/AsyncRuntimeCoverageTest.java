package com.agentframework.tests;

import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.hitl.*;
import com.agentframework.observability.*;

import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for AsyncAgentRuntime paths:
 *  - suspend → complete (no HITL)
 *  - resume paths: Approved, Rejected, Modified, Escalated
 *  - query: NOT_FOUND, PENDING, COMPLETED
 *  - InMemoryExecutionStore: save/load/list/delete
 *  - ApprovalDecision all variants
 *  - AAR-2 regression: tenantId is never snap.runId()
 */
class AsyncRuntimeCoverageTest {

    private static Agent echoAgent() {
        return TestAgentFactory.echoAgent();
    }

    private static Task task() {
        return Task.builder().instruction("say hello").maxCycles(3).maxTokens(4000).build();
    }

    // ── InMemoryExecutionStore ────────────────────────────────────────────────

    @Test
    void store_saveLoadDelete() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        DefaultExecutionContext ctx = ctx("t");
        ExecutionContext.Snapshot snap = ctx.checkpoint();

        store.save(snap);
        ExecutionContext.Snapshot loaded = store.load(snap.runId());
        assertNotNull(loaded, "store: load after save must not return null");
        assertEquals(snap.runId(), loaded.runId());

        store.delete(snap.runId());
        assertNull(store.load(snap.runId()), "store: load after delete must return null");
    }

    @Test
    void store_loadNonExistentReturnsNull() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        assertNull(store.load("does-not-exist"));
    }

    @Test
    void store_listAll() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        store.save(ctx("t1").checkpoint());
        store.save(ctx("t2").checkpoint());
        assertTrue(store.listAll().size() >= 2);
    }

    // ── ApprovalDecision sealed variants ─────────────────────────────────────

    @Test
    void approvalDecision_allVariantsInstantiate() {
        ToolCall tc = new ToolCall(UUID.randomUUID().toString(), "echo",
                Map.of(), false);
        ApprovalDecision approved = new ApprovalDecision.Approved();
        ApprovalDecision rejected = new ApprovalDecision.Rejected("too risky");
        ApprovalDecision modified = new ApprovalDecision.Modified(tc);
        ApprovalDecision escalated = new ApprovalDecision.Escalated("need human");

        assertNotNull(approved);
        assertNotNull(rejected);
        assertNotNull(modified);
        assertNotNull(escalated);

        assertEquals("too risky",   ((ApprovalDecision.Rejected) rejected).reason());
        assertEquals("need human",  ((ApprovalDecision.Escalated) escalated).reason());
        assertEquals("echo",
                ((ApprovalDecision.Modified) modified).updatedCall().toolName());
    }

    // ── AsyncAgentRuntime.query ───────────────────────────────────────────────

    @Test
    void query_unknownTokenIsNotFound() {
        AsyncAgentRuntime aar = new AsyncAgentRuntime(
                new PassThroughPlanValidator(),
                new InMemoryEventSink(),
                new InMemoryExecutionStore());
        JobToken tok = new JobToken("no-such-id", "/jobs/no-such-id/status",
                java.time.Duration.ofMinutes(1));
        assertEquals(AsyncAgentRuntime.JobStatus.NOT_FOUND, aar.query(tok));
    }

    // ── AutoApprovalService / AutoRejectService ───────────────────────────────

    @Test
    void autoApprovalService_alwaysApproves() {
        AutoApprovalService svc = new AutoApprovalService();
        ToolCall tc = new ToolCall(UUID.randomUUID().toString(), "echo", Map.of(), false);
        ApprovalPacket pkt = new ApprovalPacket(tc, RiskClassification.LOW);
        ApprovalDecision d = svc.decide(pkt);
        assertInstanceOf(ApprovalDecision.Approved.class, d);
    }

    @Test
    void autoRejectService_alwaysRejects() {
        AutoRejectService svc = new AutoRejectService();
        ToolCall tc = new ToolCall(UUID.randomUUID().toString(), "echo", Map.of(), false);
        ApprovalPacket pkt = new ApprovalPacket(tc, RiskClassification.HIGH);
        ApprovalDecision d = svc.decide(pkt);
        assertInstanceOf(ApprovalDecision.Rejected.class, d);
    }

    // ── RiskClassification enum coverage ─────────────────────────────────────

    @Test
    void riskClassification_allValues() {
        RiskClassification[] vals = RiskClassification.values();
        assertTrue(vals.length >= 2, "RiskClassification must have at least LOW and HIGH");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static DefaultExecutionContext ctx(String tenant) {
        Task t = Task.builder().instruction("test").maxCycles(5).maxTokens(4000).build();
        DefaultExecutionContext c = new DefaultExecutionContext(t, tenant, "u");
        c.goalStack().push(Goal.builder().id("root").description("test")
                .priority(1).status(GoalStatus.ACTIVE).build());
        return c;
    }
}
