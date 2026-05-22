package com.agentframework.memory;
public interface WriteGate {
    WriteDecision evaluate(MemoryContent content, MemoryMetadata meta);
}
