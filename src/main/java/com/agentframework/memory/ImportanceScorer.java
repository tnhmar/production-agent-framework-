package com.agentframework.memory;
public interface ImportanceScorer {
    double score(MemoryRecord record, EvaluationContext ctx);
}
