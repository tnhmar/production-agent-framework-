package com.agentframework.action.middleware;

import com.agentframework.action.ToolContract;
import com.agentframework.foundation.ToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Caching middleware that stores {@link ToolResult} values keyed by a
 * SHA-256 digest of the tool name and its canonicalised arguments.
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li><b>Deterministic key:</b> {@code hashCode()} is explicitly avoided
 *       because it is not stable across JVM restarts, is not collision-resistant,
 *       and produces the same integer for structurally different maps.  SHA-256
 *       provides 256-bit collision resistance with negligible compute cost for
 *       the argument sizes seen in practice.</li>
 *   <li><b>Pluggable store:</b> The {@link CacheStore} interface lets callers
 *       substitute a Caffeine or Redis-backed implementation in production without
 *       changing this class (OCP).</li>
 *   <li><b>Tenant isolation:</b> The cache key is prefixed with the tenant id
 *       (supplied via {@link ToolInvocation#tenantId()}).  Invalidation can be
 *       scoped to a single tenant, preventing cross-tenant eviction storms.</li>
 * </ul>
 */
public class CachingMiddleware implements ToolMiddleware {

    /** Pluggable backing store — default implementation is an in-process concurrent map. */
    public interface CacheStore {
        ToolResult  get(String key);
        void        put(String key, ToolResult result, Instant expiresAt);
        boolean     isExpired(String key);
        void        remove(String key);
        void        removeByPrefix(String prefix);
        void        clear();
    }

    private final CacheStore store;
    private final Duration   ttl;

    /** Default constructor — uses the built-in in-process ConcurrentHashMap store. */
    public CachingMiddleware(Duration ttl) {
        this(ttl, new InProcessCacheStore());
    }

    /** Full constructor — inject any {@link CacheStore} implementation. */
    public CachingMiddleware(Duration ttl, CacheStore store) {
        this.ttl   = ttl;
        this.store = store;
    }

    @Override
    public ToolResult apply(ToolInvocation inv, Function<ToolInvocation, ToolResult> next) {
        if (inv.contract() == null
                || inv.contract().sideEffect() != ToolContract.SideEffectClass.READ_ONLY) {
            return next.apply(inv);
        }

        String key = buildKey(inv);
        ToolResult cached = store.get(key);
        if (cached != null && !store.isExpired(key)) {
            return cached;
        }

        ToolResult result = next.apply(inv);
        store.put(key, result, Instant.now().plus(ttl));
        return result;
    }

    // ── Key construction ───────────────────────────────────────────────

    private String buildKey(ToolInvocation inv) {
        String tenantId  = inv.tenantId()  != null ? inv.tenantId()  : "";
        String toolName  = inv.contract().name();
        String canonical = inv.arguments().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("&"));
        String raw = tenantId + "|" + toolName + "|" + canonical;
        return tenantId + ":" + sha256Hex(raw);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java Security spec (JCA/JCE).
            // If it is unavailable the JVM is non-compliant; fail-fast.
            throw new IllegalStateException(
                "SHA-256 algorithm unavailable — JVM environment is non-compliant", e);
        }
    }

    // ── Invalidation API ──────────────────────────────────────────────

    /**
     * Removes all cached entries belonging to {@code tenantId}.
     * Does not affect entries from other tenants.
     */
    public void invalidateForTenant(String tenantId) {
        store.removeByPrefix(tenantId + ":");
    }

    /** Emergency flush — removes all entries regardless of tenant. */
    public void invalidateAll() {
        store.clear();
    }

    // ── Default in-process backing store ───────────────────────────────

    private static final class InProcessCacheStore implements CacheStore {
        private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
        private record Entry(ToolResult result, Instant expiresAt) {}

        @Override public ToolResult  get(String key)   { Entry e = map.get(key); return e == null ? null : e.result(); }
        @Override public boolean     isExpired(String key) { Entry e = map.get(key); return e == null || Instant.now().isAfter(e.expiresAt()); }
        @Override public void        put(String key, ToolResult r, Instant exp) { map.put(key, new Entry(r, exp)); }
        @Override public void        remove(String key) { map.remove(key); }
        @Override public void        removeByPrefix(String prefix) { map.keySet().removeIf(k -> k.startsWith(prefix)); }
        @Override public void        clear() { map.clear(); }
    }
}
