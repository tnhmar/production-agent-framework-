package com.agentframework.memory.impl;
import com.agentframework.core.RequestContext;
import com.agentframework.memory.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
public class InMemoryRelationalStore implements RelationalStore {
    private final Map<String, MemoryRecord> records = new ConcurrentHashMap<>();

    public void insert(MemoryRecord r, RequestContext ctx)              { records.put(r.id(), r); }
    public void update(String id, MemoryRecord r, RequestContext ctx)   { records.put(id, r); }
    public Optional<MemoryRecord> get(String id, RequestContext ctx)    { return Optional.ofNullable(records.get(id)); }
    public void delete(String id, RequestContext ctx)                   { records.remove(id); }

    public List<MemoryRecord> bm25Search(String query, int k, RequestContext ctx) {
        String lower = query.toLowerCase();
        return records.values().stream()
               .filter(r -> r.content().text().toLowerCase().contains(lower))
               .sorted(Comparator.comparingDouble(MemoryRecord::score).reversed())
               .limit(k).collect(Collectors.toList());
    }
    public List<MemoryRecord> queryByMetadata(Map<String,String> filters, RequestContext ctx) {
        return records.values().stream()
               .filter(r -> filters.entrySet().stream().allMatch(f ->
                   r.meta().tags().contains(f.getKey()+":"+f.getValue())))
               .collect(Collectors.toList());
    }
    public int size() { return records.size(); }
    public Collection<MemoryRecord> all() { return records.values(); }
}
