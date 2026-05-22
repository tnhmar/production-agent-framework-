package com.agentframework.foundation;
import java.time.Instant;
public record Observation(
        String    content,
        Origin    origin,
        TrustTier trustTier,
        TaintLabel taintLabel,
        Instant   timestamp,
        String    sourceReference) {
    /** Convenience: unknown taint = CLEAN */
    public Observation(String content, Origin origin, TrustTier trustTier,
                       Instant timestamp, String sourceReference) {
        this(content, origin, trustTier, TaintLabel.CLEAN, timestamp, sourceReference);
    }
    public Observation withContent(String c) {
        return new Observation(c, origin, trustTier, taintLabel, timestamp, sourceReference);
    }
}
