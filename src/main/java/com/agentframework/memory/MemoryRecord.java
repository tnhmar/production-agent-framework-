package com.agentframework.memory;

import com.agentframework.foundation.TaintLabel;

/**
 * M3 fix: MemoryRecord now carries a {@link TaintLabel} so that taint
 * assigned at write time is preserved and restored when the record is
 * retrieved into a new Observation, preventing silent CLEAN defaults
 * for records originally written from untrusted sources.
 */
public record MemoryRecord(
        String   id,
        MemoryContent content,
        MemoryType    type,
        MemoryMetadata meta,
        double        score,
        int           accessCount,
        TaintLabel    taintLabel) {

    /** Backward-compatible constructor — defaults taintLabel to CLEAN. */
    public MemoryRecord(String id, MemoryContent content, MemoryType type,
                        MemoryMetadata meta, double score, int accessCount) {
        this(id, content, type, meta, score, accessCount, TaintLabel.CLEAN);
    }

    public MemoryRecord withAccessCount(int n) {
        return new MemoryRecord(id, content, type, meta, score, n, taintLabel);
    }
    public MemoryRecord withScore(double s) {
        return new MemoryRecord(id, content, type, meta, s, accessCount, taintLabel);
    }
    public MemoryRecord withTaintLabel(TaintLabel t) {
        return new MemoryRecord(id, content, type, meta, score, accessCount, t);
    }
}
