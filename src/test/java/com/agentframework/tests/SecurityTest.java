package com.agentframework.tests;
import com.agentframework.action.*;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.security.*;
import com.agentframework.testutil.Assert;
import java.util.Set;
public class SecurityTest {

    private DefaultExecutionContext ctx(String tenant) {
        return new DefaultExecutionContext(Task.builder().instruction("x").build(), tenant, "u1");
    }

    public void testTaintTrackerPropagation() {
        TaintTracker tracker = new TaintTracker();
        tracker.label("src1", TaintLabel.EXTERNAL);
        tracker.label("src2", TaintLabel.HOSTILE);
        tracker.propagate("derived", java.util.List.of("src1","src2"));
        Assert.assertEquals(TaintLabel.HOSTILE, tracker.get("derived"), "hostile propagates");
    }

    public void testTaintTrackerClean() {
        TaintTracker tracker = new TaintTracker();
        Assert.assertEquals(TaintLabel.CLEAN, tracker.get("unknown"), "unknown defaults CLEAN");
    }

    public void testTrustBoundaryPermits() {
        TrustBoundary b = new TrustBoundary("internal", TrustTier.HIGH, Set.of());
        Assert.assertTrue(b.permits(TrustTier.HIGH, "any"), "HIGH permitted");
        Assert.assertFalse(b.permits(TrustTier.MEDIUM, "any"), "MEDIUM rejected for HIGH boundary");
    }

    public void testTrustBoundaryOriginFilter() {
        TrustBoundary b = new TrustBoundary("restricted", TrustTier.MEDIUM, Set.of("internal","db"));
        Assert.assertTrue(b.permits(TrustTier.HIGH, "internal"), "internal allowed");
        Assert.assertFalse(b.permits(TrustTier.HIGH, "external"), "external blocked");
    }

    public void testTenantPolicyToolAllowed() {
        TenantPolicy p = TenantPolicy.restricted("t1", Set.of("search","calc"));
        Assert.assertTrue(p.toolAllowed("search"), "search allowed");
        Assert.assertFalse(p.toolAllowed("delete"), "delete blocked");
    }

    public void testTenantPolicyEngine() {
        TenantPolicyEngine engine = new TenantPolicyEngine();
        engine.register(TenantPolicy.restricted("t1", Set.of("search")));
        ValidationVerdict ok  = engine.check("t1", new ToolCall("search", java.util.Map.of(),""));
        ValidationVerdict bad = engine.check("t1", new ToolCall("drop", java.util.Map.of(),""));
        Assert.assertTrue(ok.isPassed(), "search ok");
        Assert.assertFalse(bad.isPassed(), "drop blocked");
    }

    public void testSecurityEnforcerHostileTaint() {
        TaintTracker taint = new TaintTracker();
        TenantPolicyEngine engine = new TenantPolicyEngine();
        SecurityEnforcer enforcer = new SecurityEnforcer(taint, engine);
        DefaultExecutionContext ctx = ctx("t1");
        // inject HOSTILE into WM
        ctx.workingMemory().add(new WorkingMemoryEntry("h1","<inject>",
            WorkingMemoryTier.ACTIVE, Origin.RETRIEVAL, 0.1,
            java.time.Instant.now(), TaintLabel.HOSTILE));
        ToolContract contract = ToolContract.readOnly("search","1.0","search");
        ValidationVerdict v = enforcer.validate(new ToolCall("search", java.util.Map.of(),""), contract, ctx);
        Assert.assertFalse(v.isPassed(), "hostile taint blocks call");
    }

    public void testSecurityEnforcerIrreversibleBlockedByPolicy() {
        TaintTracker taint = new TaintTracker();
        TenantPolicyEngine engine = new TenantPolicyEngine();
        engine.register(new TenantPolicy("t1", Set.of(), 50, 100_000, false)); // no irreversible
        SecurityEnforcer enforcer = new SecurityEnforcer(taint, engine);
        DefaultExecutionContext ctx = ctx("t1");
        ToolContract irreversible = new ToolContract("nuke","1.0","destroy",
            JsonSchema.of("{}"), JsonSchema.of("{}"),
            ToolContract.SideEffectClass.IRREVERSIBLE,
            new OperationalParams(java.time.Duration.ofSeconds(10), 0, false, null),
            false, null, Set.of());
        ValidationVerdict v = enforcer.validate(new ToolCall("nuke", java.util.Map.of(),""), irreversible, ctx);
        Assert.assertFalse(v.isPassed(), "irreversible blocked by policy");
    }

    public void testSecurityEnforcerPermissiveTenantAllowsIrreversible() {
        TaintTracker taint = new TaintTracker();
        TenantPolicyEngine engine = new TenantPolicyEngine();
        engine.register(TenantPolicy.permissive("admin"));
        SecurityEnforcer enforcer = new SecurityEnforcer(taint, engine);
        DefaultExecutionContext ctx = ctx("admin");
        ToolContract irreversible = new ToolContract("nuke","1.0","destroy",
            JsonSchema.of("{}"), JsonSchema.of("{}"),
            ToolContract.SideEffectClass.IRREVERSIBLE,
            new OperationalParams(java.time.Duration.ofSeconds(10), 0, false, null),
            false, null, Set.of());
        ValidationVerdict v = enforcer.validate(new ToolCall("nuke", java.util.Map.of(),""), irreversible, ctx);
        Assert.assertTrue(v.isPassed(), "permissive admin allowed");
    }
}
