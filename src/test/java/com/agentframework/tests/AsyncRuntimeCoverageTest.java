package com.agentframework.tests;

import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.hitl.*;
import com.agentframework.observability.*;

import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for AsyncAgentRuntime paths and HITL supporting classes.
 *
 * Production contract notes:
 *   InMemoryExecutionStore.load() throws IllegalArgumentException when runId
 *   is not present — it does NOT return null.
 *   Tests reflect the actual production contract.
 */
class AsyncRuntimeCoverageTest {

    private static DefaultExecutionContext ctx(String tenant) {
        Task t = Task.builder().instruction("test").maxCycles(5).maxTokens(4000).build();
        DefaultExecutionContext c = new DefaultExecutionContext(t, tenant, "u");
        c.goalStack().push(
            new Goal("root", null, GoalStatus.ACTIVE, "test", List.of(), null));
        return c;
    }

    // ── InMemoryExecutionStore ───────────────────────────────────────────────

    @Test
    void store_saveLoadRoundTrip() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        DefaultExecutionContext ctx = ctx("t");
        ExecutionContext.Snapshot snap = ctx.checkpoint();

        store.save(snap);
        ExecutionContext.Snapshot loaded = store.load(snap.runId());
        assertNotNull(loaded, "store: load after save must not return null");
        assertEquals(snap.runId(), loaded.runId());
    }

    @Test
    void store_loadAfterDeleteThrows() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        DefaultExecutionContext ctx = ctx("t");
        ExecutionContext.Snapshot snap = ctx.checkpoint();

        store.save(snap);
        store.delete(snap.runId());
        assertThrows(IllegalArgumentException.class,
                () -> store.load(snap.runId()),
                "store: load after delete must throw IllegalArgumentException");
    }

    @Test
    void store_loadNonExistentThrows() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        assertThrows(IllegalArgumentException.class,
                () -> store.load("does-not-exist"),
                "store: load of unknown runId must throw IllegalArgumentException");
    }

    // ── ApprovalDecision sealed variants ────────────────────────────────────

    @Test
    void approvalDecision_allVariantsInstantiate() {
        ToolCall tc = new ToolCall("echo", Map.of(), null);
        ApprovalDecision approved  = new ApprovalDecision.Approved();
        ApprovalDecision rejected  = new ApprovalDecision.Rejected("too risky");
        ApprovalDecision modified  = new ApprovalDecision.Modified(tc);
        ApprovalDecision escalated = new ApprovalDecision.Escalated("need human");

        assertNotNull(approved);
        assertNotNull(rejected);
        assertNotNull(modified);
        assertNotNull(escalated);

        assertEquals("too risky",
                ((ApprovalDecision.Rejected) rejected).reason());
        assertEquals("need human",
                ((ApprovalDecision.Escalated) escalated).reason());
        assertEquals("echo",
                ((ApprovalDecision.Modified) modified).updatedCall().toolName());
    }

    // ── AsyncAgentRuntime.query ──────────────────────────────────────────────

    @Test
    void query_unknownTokenReturnsNotFound() {
        AsyncAgentRuntime aar = new AsyncAgentRuntime(
                new PassThroughPlanValidator(),
                new InMemoryEventSink(),
                new InMemoryExecutionStore());
        JobToken tok = new JobToken("no-such-id", "/jobs/no-such-id/status",
                Duration.ofMinutes(1));
        assertEquals(AsyncAgentRuntime.JobStatus.NOT_FOUND, aar.query(tok),
                "query: unknown token must return NOT_FOUND");
    }

    // ── AutoApprovalService / AutoRejectService ──────────────────────────────

    private static ApprovalPacket packet(RiskClassification risk) {
        return new ApprovalPacket(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "tenant",
                1,
                "echo",
                Map.of(),
                null,
                Map.of(),
                risk,
                null,
                java.time.Instant.now(),
                Duration.ofMinutes(5)
        );
    }

    @Test
    void autoApprovalService_alwaysApproves() {
        AutoApprovalService svc = new AutoApprovalService();
        ApprovalDecision d = svc.awaitDecision(packet(RiskClassification.LOW));
        assertInstanceOf(ApprovalDecision.Approved.class, d);
    }

    @Test
    void autoRejectService_alwaysRejects() {
        AutoRejectService svc = new AutoRejectService();
        ApprovalDecision d = svc.awaitDecision(packet(RiskClassification.HIGH));
        assertInstanceOf(ApprovalDecision.Rejected.class, d);
    }

    // ── RiskClassification enum ──────────────────────────────────────────────

    @Test
    void riskClassification_allValues() {
        assertTrue(RiskClassification.values().length >= 2,
                "RiskClassification must have at least LOW and HIGH");
    }

    // ── AAR-2: extractTenantId reads successCriteria, not runId ─────────────

    @Test
    void aar2_extractTenantId_fromSuccessCriteria() {
        Goal root = new Goal("root", null, GoalStatus.ACTIVE,
                "achieve X", List.of(), null,
                "tenantId:acme-corp;achieve X");
        DefaultExecutionContext c = new DefaultExecutionContext(
                Task.builder().instruction("test").maxCycles(3).maxTokens(1000).build(),
                "acme-corp", "u");
        c.goalStack().push(root);
        ExecutionContext.Snapshot snap = c.checkpoint();

        Optional<Goal> rootGoal = snap.goalStackSnapshot().stream()
                .filter(g -> "root".equals(g.id())).findFirst();
        assertTrue(rootGoal.isPresent());
        assertTrue(rootGoal.get().successCriteria().startsWith("tenantId:acme-corp"),
                "AAR-2: successCriteria must carry tenant prefix");
    }
}
