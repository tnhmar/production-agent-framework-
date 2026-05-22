package com.agentframework.security;
import java.util.Set;
public record TenantPolicy(String tenantId, Set<String> allowedTools,
        int maxCyclesPerRun, int maxTokensPerRun, boolean allowIrreversible) {
    public static TenantPolicy permissive(String tenantId) {
        return new TenantPolicy(tenantId, Set.of(), Integer.MAX_VALUE, Integer.MAX_VALUE, true);
    }
    public static TenantPolicy restricted(String tenantId, Set<String> tools) {
        return new TenantPolicy(tenantId, tools, 20, 50_000, false);
    }
    public boolean toolAllowed(String toolName) {
        return allowedTools.isEmpty() || allowedTools.contains(toolName);
    }
}
