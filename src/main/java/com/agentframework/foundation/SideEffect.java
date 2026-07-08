package com.agentframework.foundation;

/**
 * Side-effect classification for a completed tool invocation, as recorded in
 * {@link ToolResult}.
 *
 * <p>This enum mirrors the policy table in spec Vol. 1, Ch. 7 and intentionally
 * uses the same constant names as {@link com.agentframework.action.ToolContract.SideEffectClass}
 * so the two can be compared without mapping tables.  {@code ToolContract.SideEffectClass}
 * declares the <em>expected</em> side-effect class before execution; this enum
 * records the <em>actual</em> side-effect class on the returned {@link ToolResult}.
 *
 * <table>
 *   <tr><th>Constant</th><th>Policy implication</th></tr>
 *   <tr><td>{@link #READ_ONLY}</td><td>No state change — permit by default</td></tr>
 *   <tr><td>{@link #IDEMPOTENT_WRITE}</td><td>Safe to retry — permit with schema validation</td></tr>
 *   <tr><td>{@link #WRITE_NON_IDEMPOTENT}</td><td>Must not be retried blindly — require deduplication control</td></tr>
 *   <tr><td>{@link #IRREVERSIBLE}</td><td>Cannot be undone — requires human approval</td></tr>
 *   <tr><td>{@link #HIGH_BLAST_RADIUS}</td><td>Broad impact — requires staged execution and risk review</td></tr>
 * </table>
 */
public enum SideEffect {

    /**
     * The tool read data only; no external state was modified.
     * Used as the baseline in {@link ToolResult#indicatesWorldChange()} and
     * as the default for {@link ToolResult#ok(Object)}.
     */
    READ_ONLY,

    /**
     * The tool wrote state in an idempotent manner (repeating the call
     * produces the same outcome).  Retries are safe after transient failures.
     */
    IDEMPOTENT_WRITE,

    /**
     * The tool wrote state in a non-idempotent manner (repeating the call
     * would cause duplicate side-effects).  Used as the default for
     * {@link ToolResult#write(Object)}.
     */
    WRITE_NON_IDEMPOTENT,

    /**
     * The tool performed an action that cannot be reversed (e.g. sent an
     * e-mail, published a record, deleted data permanently).  Requires human
     * approval before execution.
     */
    IRREVERSIBLE,

    /**
     * The tool affects a wide blast radius (e.g. infrastructure changes,
     * bulk operations).  Requires staged execution and risk review.
     */
    HIGH_BLAST_RADIUS
}
