package com.agentframework.security;
import com.agentframework.foundation.TaintLabel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/** Tracks taint labels for data items by ID. */
public class TaintTracker {
    private final Map<String, TaintLabel> labels = new ConcurrentHashMap<>();

    public void label(String id, TaintLabel label) { labels.put(id, label); }
    public TaintLabel get(String id) { return labels.getOrDefault(id, TaintLabel.CLEAN); }
    public boolean isHostile(String id) { return get(id) == TaintLabel.HOSTILE; }
    public boolean isExternal(String id){ return get(id) != TaintLabel.CLEAN; }
    public void clear(String id) { labels.remove(id); }
    public int size() { return labels.size(); }

    /** Propagate taint: if any source is tainted, the derived ID inherits max taint. */
    public void propagate(String derivedId, List<String> sourceIds) {
        TaintLabel max = sourceIds.stream()
            .map(this::get)
            .reduce(TaintLabel.CLEAN, (a, b) -> severity(a) >= severity(b) ? a : b);
        labels.put(derivedId, max);
    }
    private int severity(TaintLabel l) {
        return switch (l) { case CLEAN -> 0; case EXTERNAL -> 1; case HOSTILE -> 2; };
    }
}
