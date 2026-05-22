package com.agentframework.memory.impl;
import com.agentframework.core.RequestContext;
import com.agentframework.memory.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
public class InMemoryVectorStore implements VectorStore {
    private final Map<String, List<Double>> vectors  = new ConcurrentHashMap<>();
    private final Map<String, String>       payloads = new ConcurrentHashMap<>();

    public void insert(String id, List<Double> emb, String payload, RequestContext ctx) {
        vectors.put(id, emb); payloads.put(id, payload);
    }
    public List<Neighbor> search(List<Double> query, int k, RequestContext ctx) {
        return vectors.entrySet().stream()
               .map(e -> new Neighbor(e.getKey(), cosine(query, e.getValue())))
               .sorted(Comparator.comparingDouble(Neighbor::score).reversed())
               .limit(k).collect(Collectors.toList());
    }
    public void delete(String id, RequestContext ctx)         { vectors.remove(id); payloads.remove(id); }
    public String getPayload(String id, RequestContext ctx)   { return payloads.get(id); }

    private double cosine(List<Double> a, List<Double> b) {
        double dot=0, na=0, nb=0;
        int len = Math.min(a.size(), b.size());
        for (int i=0;i<len;i++){dot+=a.get(i)*b.get(i);na+=a.get(i)*a.get(i);nb+=b.get(i)*b.get(i);}
        return dot / (Math.sqrt(na)*Math.sqrt(nb) + 1e-8);
    }
    public int size() { return vectors.size(); }
}
