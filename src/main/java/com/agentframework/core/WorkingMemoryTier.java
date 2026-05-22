package com.agentframework.core;

/**
 * Tier hierarchy for working memory entries.
 *
 * <p>Eviction priority (lowest number evicted first — M2 fix):
 * <pre>
 *   ARCHIVED(0) → COMPRESSED(1) → BACKGROUND(2) → ACTIVE(3)
 * </pre>
 */
public enum WorkingMemoryTier {
    /** Lowest priority — fully archived entries, evicted first. */
    ARCHIVED,
    /** Compressed/summarized entries — evicted second. */
    COMPRESSED,
    /** Secondary-tier background context — evicted third. */
    BACKGROUND,
    /**
     * @deprecated Use BACKGROUND. Kept for binary compatibility.
     */
    @Deprecated
    SECONDARY,
    /** Active working set — evicted last. */
    ACTIVE
}
