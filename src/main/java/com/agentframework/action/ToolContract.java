package com.agentframework.action;
import com.agentframework.foundation.JsonSchema;
import java.time.Duration;
import java.util.Set;
public record ToolContract(
        String name, String version, String description,
        JsonSchema inputSchema, JsonSchema outputSchema,
        SideEffectClass sideEffect, OperationalParams operationalParams,
        boolean isLongRunning, LongRunningProtocol longRunningProtocol,
        Set<String> dataResidencyZones) {
    public enum SideEffectClass { READ_ONLY, IDEMPOTENT_WRITE, NON_IDEMPOTENT_WRITE, IRREVERSIBLE }
    public enum LongRunningProtocol { SUBMIT_POLL, WEBHOOK }
    /** Convenience builder for simple read-only tools. */
    public static ToolContract readOnly(String name, String version, String description) {
        return new ToolContract(name, version, description,
            JsonSchema.of("{}"), JsonSchema.of("{}"),
            SideEffectClass.READ_ONLY,
            new OperationalParams(Duration.ofSeconds(30), 3, true, null),
            false, null, Set.of());
    }
    public static ToolContract write(String name, String version, String description) {
        return new ToolContract(name, version, description,
            JsonSchema.of("{}"), JsonSchema.of("{}"),
            SideEffectClass.NON_IDEMPOTENT_WRITE,
            new OperationalParams(Duration.ofSeconds(30), 1, false, null),
            false, null, Set.of());
    }
}
