package com.agentframework.perception;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.Observation;
public interface GroundingService {
    Observation ground(Observation obs, ExecutionContext ctx);
    static GroundingService identity() { return (obs, ctx) -> obs; }
}
