package com.agentframework.action.middleware;
import com.agentframework.foundation.ToolResult;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Function;
public class RateLimitingMiddleware implements ToolMiddleware {
    private final Map<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final int maxConcurrent;
    public RateLimitingMiddleware(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public ToolResult apply(ToolInvocation inv, Function<ToolInvocation, ToolResult> next) {
        Semaphore sem = permits.computeIfAbsent(inv.contract().name(), k -> new Semaphore(maxConcurrent));
        if (!sem.tryAcquire())
            throw new RuntimeException("Rate limit exceeded for " + inv.contract().name());
        try { return next.apply(inv); } finally { sem.release(); }
    }
}
