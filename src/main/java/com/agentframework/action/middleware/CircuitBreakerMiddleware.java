package com.agentframework.action.middleware;
import com.agentframework.foundation.ToolResult;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
public class CircuitBreakerMiddleware implements ToolMiddleware {
    private final int  failureThreshold;
    private final long openTimeoutMs;
    private final AtomicInteger failureCount   = new AtomicInteger();
    private final AtomicLong    lastFailureTime = new AtomicLong();
    private volatile boolean open = false;
    public CircuitBreakerMiddleware(int failureThreshold, long openTimeoutMs){
        this.failureThreshold=failureThreshold; this.openTimeoutMs=openTimeoutMs;}
    public ToolResult apply(ToolInvocation inv, Function<ToolInvocation, ToolResult> next) {
        if (open) {
            if (System.currentTimeMillis() - lastFailureTime.get() > openTimeoutMs) {
                open = false; failureCount.set(0);
            } else throw new RuntimeException("Circuit open for " + inv.contract().name());
        }
        try {
            ToolResult r = next.apply(inv); failureCount.set(0); return r;
        } catch (RuntimeException e) {
            if (failureCount.incrementAndGet() >= failureThreshold) {
                open = true; lastFailureTime.set(System.currentTimeMillis());
            }
            throw e;
        }
    }
    public boolean isOpen() { return open; }
    public void    reset()  { open=false; failureCount.set(0); }
}
