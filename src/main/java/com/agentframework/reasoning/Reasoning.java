package com.agentframework.reasoning;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.*;
public interface Reasoning {
    Decision decide(ExecutionContext ctx, Observations obs);
}
