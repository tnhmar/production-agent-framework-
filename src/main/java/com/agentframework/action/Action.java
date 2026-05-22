package com.agentframework.action;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.*;
public interface Action {
    ActionResult execute(Decision decision, ExecutionContext ctx);
}
