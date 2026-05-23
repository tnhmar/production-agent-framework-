package com.agentframework.security;

import com.agentframework.action.*;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.*;

/**
 * IC6 fix: SecurityEnforcer now correctly handles both ToolCall and the individual
 * calls inside a ParallelToolCalls decision when invoked as an ActionValidator.
 *
 * <p>When called via the ActionValidator interface (single ToolCall), validates:
 * <ol>
 *   <li>Tenant policy allows the tool.</li>
 *   <li>No HOSTILE taint is present in working memory.</li>
 *   <li>IRREVERSIBLE actions are permitted by tenant policy.</li>
 * </ol>
 */
public class SecurityEnforcer implements ActionValidator {
    private final TaintTracker       taint;
    private final TenantPolicyEngine policyEngine;

    public SecurityEnforcer(TaintTracker taint, TenantPolicyEngine policyEngine) {
        this.taint = taint;
        this.policyEngine = policyEngine;
    }

    @Override
    public ValidationVerdict validate(ToolCall call, ToolContract contract, ExecutionContext ctx) {
        // 1. Tenant policy check
        ValidationVerdict tv = policyEngine.check(ctx.tenantId(), call);
        if (!tv.isPassed()) return tv;

        // 2. Hostile taint in working memory — block all tool dispatch
        boolean hostile = ctx.workingMemory().getAll().stream()
            .anyMatch(e -> e.taintLabel() == TaintLabel.HOSTILE);
        if (hostile) return ValidationVerdict.failed("Hostile taint detected in working memory; blocked");

        // 3. Irreversible action — check tenant permission
        // Note (IC2 fix): SafetyActionValidator runs before this and emits REQUIRE_APPROVAL.
        // This check hard-fails only when the tenant policy explicitly disallows irreversible actions.
        if (contract != null && contract.sideEffect() == ToolContract.SideEffectClass.IRREVERSIBLE) {
            TenantPolicy p = policyEngine.get(ctx.tenantId());
            if (!p.allowIrreversible())
                return ValidationVerdict.failed(
                    "Irreversible actions not permitted for tenant: " + ctx.tenantId());
        }

        return ValidationVerdict.ok();
    }

    /**
     * IC6 fix: validate each individual call in a parallel batch against tenant policy and taint.
     * Called explicitly by DefaultAction before fanning out parallel tool calls.
     */
    public ValidationVerdict validateParallel(
            java.util.List<ToolCall> calls,
            java.util.function.Function<String, ToolContract> contractLookup,
            ExecutionContext ctx) {

        boolean hostile = ctx.workingMemory().getAll().stream()
            .anyMatch(e -> e.taintLabel() == TaintLabel.HOSTILE);
        if (hostile) return ValidationVerdict.failed("Hostile taint detected; parallel batch blocked");

        for (ToolCall tc : calls) {
            ValidationVerdict tv = policyEngine.check(ctx.tenantId(), tc);
            if (!tv.isPassed()) return tv;

            ToolContract contract = contractLookup.apply(tc.toolName());
            if (contract != null && contract.sideEffect() == ToolContract.SideEffectClass.IRREVERSIBLE) {
                TenantPolicy p = policyEngine.get(ctx.tenantId());
                if (!p.allowIrreversible())
                    return ValidationVerdict.failed(
                        "Irreversible parallel tool not permitted for tenant: " + ctx.tenantId());
            }
        }
        return ValidationVerdict.ok();
    }
}
