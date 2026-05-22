package com.agentframework.action.middleware;
import com.agentframework.action.ToolContract;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.ValidationVerdict;
import java.util.Map;
public record ToolInvocation(
        ToolContract     contract,
        Map<String,Object> arguments,
        ExecutionContext  ctx,
        ValidationVerdict validationVerdict) {}
