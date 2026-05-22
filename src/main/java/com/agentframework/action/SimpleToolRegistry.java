package com.agentframework.action;
import com.agentframework.foundation.JsonSchema;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
/**
 * In-process registry. Uses keyword overlap for topK (no external embedding needed for tests).
 * Production code should swap in DefaultToolRegistry with a real EmbeddingModel.
 */
public class SimpleToolRegistry implements ToolRegistry {
    private final Map<String, ToolContract> contracts = new ConcurrentHashMap<>();
    private final Map<String, ToolHandler>  handlers  = new ConcurrentHashMap<>();

    private String key(String name, String version) { return name + ":" + version; }
    private String latestKey(String name) {
        return contracts.keySet().stream()
               .filter(k -> k.startsWith(name + ":"))
               .max(Comparator.naturalOrder()).orElse(null);
    }

    public void register(ToolContract c, ToolHandler h) {
        String k = key(c.name(), c.version());
        contracts.put(k, c); handlers.put(k, h);
        // also register by bare name for lookup convenience
        contracts.put(c.name(), c); handlers.put(c.name(), h);
    }
    public void deregister(String name) {
        String k = latestKey(name);
        if (k != null) { contracts.remove(k); handlers.remove(k); }
        contracts.remove(name); handlers.remove(name);
    }
    public ToolContract lookup(String name) {
        ToolContract c = contracts.get(name);
        if (c != null) return c;
        String k = latestKey(name); return k != null ? contracts.get(k) : null;
    }
    public List<ToolContract> list(ToolFilter f) {
        return contracts.values().stream().distinct().filter(f::matches).collect(Collectors.toList());
    }
    public Optional<JsonSchema> schemaFor(String name) {
        ToolContract c = lookup(name); return c != null ? Optional.of(c.inputSchema()) : Optional.empty();
    }
    public List<ToolContract> topK(String query, int k) {
        String q = query.toLowerCase();
        return contracts.values().stream().distinct()
               .sorted(Comparator.comparingLong((ToolContract c) ->
                   Arrays.stream(q.split("\s+"))
                         .filter(w -> c.description().toLowerCase().contains(w))
                         .count()).reversed())
               .limit(k).collect(Collectors.toList());
    }
    public void registerWithAlias(String alias, String canonical, String version) {
        ToolContract c = contracts.get(key(canonical, version));
        ToolHandler  h = handlers.get(key(canonical, version));
        if (c != null) { contracts.put(alias, c); handlers.put(alias, h); }
    }
    public List<ToolContract> listVersions(String name) {
        return contracts.entrySet().stream()
               .filter(e -> e.getKey().startsWith(name + ":"))
               .map(Map.Entry::getValue).collect(Collectors.toList());
    }
    public void deprecate(String name, String version, Instant sunsetDate) {}
    public void remove(String name, String version) {
        String k = key(name, version);
        contracts.remove(k); handlers.remove(k);
    }
    public ToolHandler findHandler(String name) {
        ToolHandler h = handlers.get(name);
        if (h != null) return h;
        String k = latestKey(name); return k != null ? handlers.get(k) : null;
    }
}
