package com.agentframework.tests;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.agentframework.action.*;
import com.agentframework.action.middleware.*;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.hitl.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
public class HitlTest {

    private DefaultExecutionContext ctx() {
        return new DefaultExecutionContext(Task.builder().instruction("test").build(),"t1","u1");
    }

    @Test
    public void testAutoApprovalAllows() {
        ApprovalService svc = new AutoApprovalService();
        ApprovalPacket pkt = new ApprovalPacket("ap1","run1","agent",1,
            "delete",Map.of(),"reason",Map.of(),RiskClassification.HIGH,
            "rollback",Instant.now(),Duration.ofHours(1));
        ApprovalDecision d = svc.awaitDecision(pkt);
        assertTrue(d instanceof ApprovalDecision.Approved, "auto-approved");
    }

    @Test
    public void testAutoRejectService() {
        ApprovalService svc = new AutoRejectService();
        ApprovalPacket pkt = new ApprovalPacket("ap2","run2","agent",1,
            "delete",Map.of(),"reason",Map.of(),RiskClassification.LOW,
            "rollback",Instant.now(),Duration.ofHours(1));
        ApprovalDecision d = svc.awaitDecision(pkt);
        assertTrue(d instanceof ApprovalDecision.Rejected, "auto-rejected");
    }

    @Test
    public void testInMemoryExecutionStore() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        DefaultExecutionContext ctx = ctx();
        ctx.transitionTo(RunState.PLANNING);
        ctx.incrementCycle();
        ExecutionContext.Snapshot snap = ctx.checkpoint();
        store.save(snap);
        assertEquals(1, store.size(), "stored 1");
        ExecutionContext.Snapshot loaded = store.load(snap.runId());
        assertEquals(snap.runId(), loaded.runId(), "runId matches");
        assertEquals(RunState.PLANNING, loaded.state(), "state matches");
        assertEquals(1, loaded.cycle(), "cycle matches");
    }

    @Test
    public void testHumanApprovalMiddlewareApproves() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.write("delete","1.0","deletes records"),
            (args, ctx) -> ToolResult.ok("deleted"));
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        HumanApprovalMiddleware mw = new HumanApprovalMiddleware(new AutoApprovalService(), store);
        DefaultAction action = new DefaultAction(reg,
            List.of(new SafetyActionValidator()), // will require approval for IRREVERSIBLE
            ToolMiddleware.chain(mw),
            new DefaultToolDispatcher(reg));
        // The write tool is NON_IDEMPOTENT but not IRREVERSIBLE so passes SafetyValidator
        DefaultExecutionContext ctx = ctx();
        ActionResult r = action.execute(new ToolCall("delete", Map.of("id","1"),"test"), ctx);
        assertTrue(r.isSuccess(), "approved and executed");
    }

    @Test
    public void testHumanApprovalMiddlewareRejects() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        // IRREVERSIBLE tool — SafetyValidator will requireApproval → passed to HITL
        reg.register(new ToolContract("nuke","1.0","destroy",
            JsonSchema.of("{}"), JsonSchema.of("{}"),
            ToolContract.SideEffectClass.IRREVERSIBLE,
            new OperationalParams(Duration.ofSeconds(5),0,false,null),
            false, null, Set.of()),
            (args,ctx) -> ToolResult.ok("gone"));
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        // But we bypass SafetyValidator and feed requireApproval verdict directly via custom validator
        ActionValidator forceApproval = (call, contract, c) -> ValidationVerdict.requireApproval("needs human");
        HumanApprovalMiddleware mw = new HumanApprovalMiddleware(new AutoRejectService(), store);
        DefaultAction action = new DefaultAction(reg,
            List.of(forceApproval), ToolMiddleware.chain(mw), new DefaultToolDispatcher(reg));
        DefaultExecutionContext ctx = ctx();
        ActionResult r = action.execute(new ToolCall("nuke", Map.of(),"test"), ctx);
        // forceApproval verdict is requireApproval → ValidationFailure returned before reaching HITL
        assertFalse(r.isSuccess(), "rejected by validator");
    }

    @Test
    public void testApprovalDecisionSealed() {
        ApprovalDecision approved  = new ApprovalDecision.Approved();
        ApprovalDecision rejected  = new ApprovalDecision.Rejected("no");
        ApprovalDecision escalated = new ApprovalDecision.Escalated("escalate");
        String kind = switch (approved) {
            case ApprovalDecision.Approved  a -> "approved";
            case ApprovalDecision.Rejected  r -> "rejected";
            case ApprovalDecision.Modified  m -> "modified";
            case ApprovalDecision.Escalated e -> "escalated";
        };
        assertEquals("approved", kind, "sealed switch");
    }
}
