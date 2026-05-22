package com.agentframework.rag;
import java.util.List;
public interface RagService {
    List<Passage> retrieve(RagQuery query);
    static RagService empty() { return q -> List.of(); }
}
