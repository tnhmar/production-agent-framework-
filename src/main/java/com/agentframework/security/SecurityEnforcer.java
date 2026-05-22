package com.agentframework.security;
import com.agentframework.action.*;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.*;
public class SecurityEnforcer implements ActionValidator {
    private final TaintTracker       taint;
    private final TenantPolicyEngine policyEngine;
    public SecurityEnforcer(TaintTracker taint, TenantPolicyEngine policyEngine) {
        this.taint=taint; this.policyEngine=policyEngine;
    }
    public ValidationVerdict validate(ToolCall call, ToolContract contract, ExecutionContext ctx) {
        ValidationVerdict tv = policyEngine.check(ctx.tenantId(), call);
        if (!tv.isPassed()) return tv;
        boolean hostile = ctx.workingMemory().getAll().stream()
            .anyMatch(e -> e.taintLabel() == TaintLabel.HOSTILE);
        if (hostile) return ValidationVerdict.failed("Hostile taint detected; blocked");
        if (contract != null && contract.sideEffect() == ToolContract.SideEffectClass.IRREVERSIBLE) {
            TenantPolicy p = policyEngine.get(ctx.tenantId());
            if (!p.allowIrreversible())
                return ValidationVerdict.failed("Irreversible actions not permitted for tenant " + ctx.tenantId());
        }
        return ValidationVerdict.ok();
    }
}
