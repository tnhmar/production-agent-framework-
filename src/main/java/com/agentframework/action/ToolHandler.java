package com.agentframework.action;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.ToolResult;
import java.util.Map;
public interface ToolHandler {
    ToolResult execute(Map<String, Object> arguments, ExecutionContext ctx) throws ToolException;
}
