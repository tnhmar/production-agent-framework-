package com.agentframework.action;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.*;
public interface ActionValidator {
    ValidationVerdict validate(ToolCall call, ToolContract contract, ExecutionContext ctx);
}
