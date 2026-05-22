package com.agentframework.core;
import java.time.Instant;
/** S-P-O belief (subject/predicate/object) with confidence and provenance. */
public record Belief(
        String  beliefId,
        String  subject,
        String  predicate,
        String  object,
        double  confidence,
        String  provenance,
        Instant acquiredAt,
        boolean conflicted) {
    public Belief withConflicted(boolean c) {
        return new Belief(beliefId, subject, predicate, object, confidence, provenance, acquiredAt, c);
    }
    public Belief withConfidence(double c) {
        return new Belief(beliefId, subject, predicate, object, c, provenance, acquiredAt, conflicted);
    }
}
