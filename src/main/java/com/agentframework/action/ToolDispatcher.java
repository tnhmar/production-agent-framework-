package com.agentframework.action;
import com.agentframework.action.middleware.ToolInvocation;
import com.agentframework.foundation.ToolResult;
public interface ToolDispatcher {
    ToolResult dispatch(ToolInvocation invocation) throws ToolException;
}
