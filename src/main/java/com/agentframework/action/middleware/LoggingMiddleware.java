package com.agentframework.action.middleware;
import com.agentframework.foundation.ToolResult;
import java.util.function.Function;
public class LoggingMiddleware implements ToolMiddleware {
    public ToolResult apply(ToolInvocation inv, Function<ToolInvocation, ToolResult> next) {
        System.out.printf("[TOOL] >> %s  args=%s%n", inv.contract().name(), inv.arguments());
        ToolResult r = next.apply(inv);
        System.out.printf("[TOOL] << %s  data=%s  tokens=%d%n",
            inv.contract().name(), r.data(), r.tokensUsed());
        return r;
    }
}
