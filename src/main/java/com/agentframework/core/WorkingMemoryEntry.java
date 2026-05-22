package com.agentframework.core;
import com.agentframework.foundation.*;
import java.time.Instant;
public record WorkingMemoryEntry(
        String id, String content, WorkingMemoryTier tier,
        Origin origin, double relevanceScore,
        Instant timestamp, TaintLabel taintLabel) {}
