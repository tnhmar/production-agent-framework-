package com.agentframework.core;
import java.time.Instant;
public record BeliefConflict(
        String  existingBeliefId,
        String  subject,
        String  predicate,
        String  existingObject,
        String  incomingObject,
        double  existingConfidence,
        double  incomingConfidence,
        String  incomingSourceId,
        Instant detectedAt) {}
