package com.agentframework.perception.stages;
import com.agentframework.foundation.*;
import java.time.Instant;
public record AnnotatedContent(NormalizedContent content, InputOrigin origin,
        TrustTier trustTier, Instant timestamp, String sourceReference) {}
