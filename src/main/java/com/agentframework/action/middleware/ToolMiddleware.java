package com.agentframework.action.middleware;
import com.agentframework.foundation.ToolResult;
import java.util.function.Function;
public interface ToolMiddleware {
    ToolResult apply(ToolInvocation inv, Function<ToolInvocation, ToolResult> next);

    static ToolMiddleware identity() {
        return (inv, next) -> next.apply(inv);
    }
    static ToolMiddleware chain(ToolMiddleware... middlewares) {
        return (inv, next) -> {
            Function<ToolInvocation, ToolResult> cur = next;
            for (int i = middlewares.length - 1; i >= 0; i--) {
                final Function<ToolInvocation, ToolResult> prev = cur;
                final ToolMiddleware mw = middlewares[i];
                cur = in -> mw.apply(in, prev);
            }
            return cur.apply(inv);
        };
    }
}
