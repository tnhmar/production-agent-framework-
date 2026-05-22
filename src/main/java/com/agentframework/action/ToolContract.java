package com.agentframework.action;

import com.agentframework.foundation.JsonSchema;
import java.time.Duration;
import java.util.Set;

/**
 * Declares the contract for a tool: identity, schemas, side-effect class,
 * operational parameters, and residency constraints.
 *
 * <p>m7 fix: {@code version} field is present for schema-drift detection in CI.
 * {@link SideEffectClass} now includes {@code HIGH_BLAST_RADIUS} to match the
 * full spec safety-classification table (Ch. 7).
 */
public record ToolContract(
        String name, String version, String description,
        JsonSchema inputSchema, JsonSchema outputSchema,
        SideEffectClass sideEffect, OperationalParams operationalParams,
        boolean isLongRunning, LongRunningProtocol longRunningProtocol,
        Set<String> dataResidencyZones) {

    /**
     * Tool side-effect classification — maps directly to Ch. 7 policy table.
     *
     * <table>
     *   <tr><th>Class</th><th>Policy implication</th></tr>
     *   <tr><td>READ_ONLY</td><td>Permit by default</td></tr>
     *   <tr><td>IDEMPOTENT_WRITE</td><td>Permit with schema validation</td></tr>
     *   <tr><td>NON_IDEMPOTENT_WRITE</td><td>Require deduplication or explicit control</td></tr>
     *   <tr><td>IRREVERSIBLE</td><td>Require human approval or elevated authorization</td></tr>
     *   <tr><td>HIGH_BLAST_RADIUS</td><td>Require staged execution and risk review</td></tr>
     * </table>
     */
    public enum SideEffectClass {
        READ_ONLY,
        IDEMPOTENT_WRITE,
        NON_IDEMPOTENT_WRITE,
        IRREVERSIBLE,
        HIGH_BLAST_RADIUS   // m7 fix: was missing from the enum despite being in the spec table
    }

    public enum LongRunningProtocol { SUBMIT_POLL, WEBHOOK }

    /** Read-only tool with 3 retries, idempotent. */
    public static ToolContract readOnly(String name, String version, String description) {
        return new ToolContract(name, version, description,
            JsonSchema.of("{}"), JsonSchema.of("{}"),
            SideEffectClass.READ_ONLY,
            new OperationalParams(Duration.ofSeconds(30), 3, true, null),
            false, null, Set.of());
    }

    /** Non-idempotent write tool — 1 attempt, no auto-retry. */
    public static ToolContract write(String name, String version, String description) {
        return new ToolContract(name, version, description,
            JsonSchema.of("{}"), JsonSchema.of("{}"),
            SideEffectClass.NON_IDEMPOTENT_WRITE,
            new OperationalParams(Duration.ofSeconds(30), 1, false, null),
            false, null, Set.of());
    }

    /** Irreversible tool — requires human approval before execution. */
    public static ToolContract irreversible(String name, String version, String description) {
        return new ToolContract(name, version, description,
            JsonSchema.of("{}"), JsonSchema.of("{}"),
            SideEffectClass.IRREVERSIBLE,
            new OperationalParams(Duration.ofSeconds(60), 0, false, null),
            false, null, Set.of());
    }

    /** High-blast-radius tool — staged execution and risk review required. */
    public static ToolContract highBlastRadius(String name, String version, String description) {
        return new ToolContract(name, version, description,
            JsonSchema.of("{}"), JsonSchema.of("{}"),
            SideEffectClass.HIGH_BLAST_RADIUS,
            new OperationalParams(Duration.ofMinutes(5), 0, false, null),
            false, null, Set.of());
    }
}
