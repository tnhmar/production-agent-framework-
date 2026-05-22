package com.agentframework.perception;
import com.agentframework.core.Goal;
import com.agentframework.foundation.Observation;
import java.util.List;
public interface RelevanceFilter {
    List<Observation> filter(List<Observation> observations, Goal goal);
    static RelevanceFilter passThrough() { return (obs, goal) -> obs; }
}
