package com.agentframework.action;
import com.agentframework.action.middleware.ToolInvocation;
import com.agentframework.foundation.ToolResult;
public class DefaultToolDispatcher implements ToolDispatcher {
    private final ToolRegistry registry;
    public DefaultToolDispatcher(ToolRegistry registry) { this.registry = registry; }
    public ToolResult dispatch(ToolInvocation inv) throws ToolException {
        ToolHandler h = registry.findHandler(inv.contract().name());
        if (h == null)
            throw new ToolException("NO_HANDLER", "No handler for: " + inv.contract().name());
        return h.execute(inv.arguments(), inv.ctx());
    }
}
