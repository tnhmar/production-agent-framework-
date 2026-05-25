package com.agentframework.reasoning.strategy;

import com.agentframework.foundation.*;
import com.agentframework.reasoning.*;
import java.util.*;

/**
 * HITL-Aware decorator strategy.
 *
 * <p>Wraps any {@link ReasoningStrategy} and adds two HITL guards:
 * <ol>
 *   <li><b>Explicit HITL marker</b>: if the raw LLM output contains
 *       {@link #HITL_MARKER}, an {@link Escalate} is returned immediately
 *       before delegate parsing — zero-cost on the nominal path.</li>
 *   <li><b>Irreversible tool guard</b>: if the parsed {@link Decision} is a
 *       {@link ToolCall} whose {@code toolName} is in the injected
 *       {@code irreversibleTools} set, an {@link Escalate} is returned.
 *       No extra LLM call is ever made.</li>
 * </ol>
 *
 * <p><b>OCP compliance</b>: this class modifies zero behaviour of the
 * delegate — it is a pure decorator.  New guards can be added by
 * subclassing or by composing another decorator layer.
 *
 * <p><b>No hardcoded strings</b> — all literals are constants in this class.
 */
public final class HitlAwareStrategy implements ReasoningStrategy {

    // ── Protocol constants ──────────────────────────────────────────────────
    /** LLM outputs this token to explicitly request human intervention. */
    public static final String HITL_MARKER         = "HITL_REQUIRED";
    /** Prefix added to the Escalate reason when an irreversible tool is blocked. */
    public static final String IRREVERSIBLE_PREFIX = "irreversible-tool-blocked:";

    // ── Escalation severity constants ───────────────────────────────────────
    private static final String SEVERITY_HITL         = JsonDecisionParser.Severity.HIGH.value;
    private static final String SEVERITY_IRREVERSIBLE = JsonDecisionParser.Severity.HIGH.value;

    // ── Escalation message templates (no inline literals in logic) ──────────
    private static final String MSG_HITL_DETECTED =
        "HITL marker detected in LLM output — human approval required";
    private static final String MSG_IRREVERSIBLE_SUFFIX =
        " requires human approval before execution";

    // ── Schema addendum ─────────────────────────────────────────────────────
    private static final String SCHEMA_HITL_ADDENDUM =
        "\nHITL rule: if the action involves irreversible side-effects or requires " +
        "human judgement, include the token '" + HITL_MARKER + "' anywhere in your response.";

    // ── State ────────────────────────────────────────────────────────────────
    private final ReasoningStrategy   delegate;
    private final Set<String>         irreversibleTools;

    /**
     * @param delegate          base strategy to wrap; must not be null.
     * @param irreversibleTools immutable set of tool names that require human approval.
     */
    public HitlAwareStrategy(ReasoningStrategy delegate,
                              Set<String> irreversibleTools) {
        this.delegate          = Objects.requireNonNull(delegate,         "delegate");
        this.irreversibleTools = Objects.requireNonNull(irreversibleTools, "irreversibleTools");
    }

    /** Factory for the common case of wrapping with an empty irreversible-tool set. */
    public static HitlAwareStrategy wrapping(ReasoningStrategy delegate) {
        return new HitlAwareStrategy(delegate, Set.of());
    }

    /** Factory when specific irreversible tools are known at build time. */
    public static HitlAwareStrategy wrapping(ReasoningStrategy delegate,
                                              Set<String> irreversibleTools) {
        return new HitlAwareStrategy(delegate, irreversibleTools);
    }

    // ── ReasoningStrategy contract ─────────────────────────────────────────

    @Override
    public String outputSchemaDescription() {
        return delegate.outputSchemaDescription() + SCHEMA_HITL_ADDENDUM;
    }

    /**
     * Applies HITL guards then delegates to the inner strategy's {@code parse()}.
     * Guard 1 (marker check) runs on raw output — before any JSON parsing.
     * Guard 2 (irreversible tool check) runs after delegate parsing — no extra LLM call.
     */
    @Override
    public Decision parse(String llmOutput) {
        // Guard 1 — explicit HITL marker in raw text
        if (llmOutput != null && llmOutput.contains(HITL_MARKER))
            return new Escalate(MSG_HITL_DETECTED, SEVERITY_HITL);

        Decision candidate = delegate.parse(llmOutput);

        // Guard 2 — irreversible tool check
        if (!irreversibleTools.isEmpty()
                && candidate instanceof ToolCall tc
                && irreversibleTools.contains(tc.toolName())) {
            return new Escalate(
                IRREVERSIBLE_PREFIX + tc.toolName() + MSG_IRREVERSIBLE_SUFFIX,
                SEVERITY_IRREVERSIBLE);
        }

        return candidate;
    }

    /** Exposes delegate name for observability/logging. */
    @Override
    public String toString() {
        return "HitlAware[" + delegate.getClass().getSimpleName() + "]";
    }
}
