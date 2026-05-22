package com.agentframework.hitl;
import com.agentframework.core.ExecutionContext;
public interface ExecutionStore {
    void save(ExecutionContext.Snapshot snapshot);
    ExecutionContext.Snapshot load(String runId);
    void delete(String runId);
}
