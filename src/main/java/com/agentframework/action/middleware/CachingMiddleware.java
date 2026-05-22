package com.agentframework.action.middleware;
import com.agentframework.action.ToolContract;
import com.agentframework.foundation.ToolResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
public class CachingMiddleware implements ToolMiddleware {
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Duration ttl;
    public CachingMiddleware(Duration ttl) { this.ttl = ttl; }
    public ToolResult apply(ToolInvocation inv, Function<ToolInvocation, ToolResult> next) {
        if (inv.contract().sideEffect() != ToolContract.SideEffectClass.READ_ONLY)
            return next.apply(inv);
        String key = inv.contract().name() + ":" + inv.arguments().hashCode();
        CacheEntry e = cache.get(key);
        if (e != null && Instant.now().isBefore(e.expiresAt)) return e.result;
        ToolResult r = next.apply(inv);
        cache.put(key, new CacheEntry(r, Instant.now().plus(ttl)));
        return r;
    }
    private record CacheEntry(ToolResult result, Instant expiresAt) {}
    public void invalidate() { cache.clear(); }
    public int  cacheSize()  { return cache.size(); }
}
