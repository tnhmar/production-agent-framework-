package com.agentframework.security;
import com.agentframework.foundation.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public class TenantPolicyEngine {
    private final Map<String, TenantPolicy> policies = new ConcurrentHashMap<>();

    public void register(TenantPolicy policy) { policies.put(policy.tenantId(), policy); }

    public TenantPolicy get(String tenantId) {
        return policies.getOrDefault(tenantId, TenantPolicy.permissive(tenantId));
    }

    public ValidationVerdict check(String tenantId, Decision decision) {
        TenantPolicy p = get(tenantId);
        return switch (decision) {
            case ToolCall tc -> {
                if (!p.toolAllowed(tc.toolName()))
                    yield ValidationVerdict.failed("Tool not allowed for tenant " + tenantId + ": " + tc.toolName());
                yield ValidationVerdict.ok();
            }
            case ParallelToolCalls ptc -> {
                for (ToolCall tc : ptc.calls()) {
                    if (!p.toolAllowed(tc.toolName()))
                        yield ValidationVerdict.failed("Parallel tool not allowed: " + tc.toolName());
                }
                yield ValidationVerdict.ok();
            }
            default -> ValidationVerdict.ok();
        };
    }
}
