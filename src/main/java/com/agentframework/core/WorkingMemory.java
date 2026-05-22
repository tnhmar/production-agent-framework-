package com.agentframework.core;
import com.agentframework.foundation.*;
import java.util.List;
public interface WorkingMemory {
    void   add(WorkingMemoryEntry e);
    List<WorkingMemoryEntry> getAll();
    List<WorkingMemoryEntry> getByOrigin(Origin o);
    List<WorkingMemoryEntry> getUnprocessed();
    void   markProcessed(String id);
    boolean isProcessed(String id);
    void   evictOldest(int count);
    void   evictLowestRelevance(int count);
    void   compress(List<String> ids, String summary);
    int    estimatedTokenCount();
    int    size();
    void   clear();
}
