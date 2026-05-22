package com.agentframework.hitl;
import com.agentframework.core.ExecutionContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
public class InMemoryExecutionStore implements ExecutionStore {
    private final Map<String, ExecutionContext.Snapshot> store = new ConcurrentHashMap<>();
    public void save(ExecutionContext.Snapshot s) { store.put(s.runId(), s); }
    public ExecutionContext.Snapshot load(String runId) {
        ExecutionContext.Snapshot s = store.get(runId);
        if (s == null) throw new IllegalArgumentException("No snapshot for runId: " + runId);
        return s;
    }
    public void delete(String runId) { store.remove(runId); }
    public int size() { return store.size(); }
}
