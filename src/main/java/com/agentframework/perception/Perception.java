package com.agentframework.perception;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.Observations;
public interface Perception {
    Observations perceive(ExecutionContext ctx);
}
