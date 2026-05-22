package com.agentframework.action.middleware;
import com.agentframework.foundation.ToolResult;
import java.util.function.Function;
public class RetryMiddleware implements ToolMiddleware {
    private final int  maxRetries;
    private final long backoffMs;
    public RetryMiddleware(int maxRetries, long backoffMs){this.maxRetries=maxRetries;this.backoffMs=backoffMs;}
    public ToolResult apply(ToolInvocation inv, Function<ToolInvocation, ToolResult> next) {
        int attempt = 0;
        while (true) {
            try { return next.apply(inv); }
            catch (RuntimeException e) {
                if (++attempt > maxRetries) throw e;
                try { Thread.sleep(backoffMs * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }
}
