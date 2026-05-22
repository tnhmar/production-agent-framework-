package com.agentframework.memory.impl;
import com.agentframework.core.RequestContext;
import com.agentframework.memory.ObjectStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
public class InMemoryObjectStore implements ObjectStore {
    private final Map<String, byte[]> data = new ConcurrentHashMap<>();
    public void   put(String k, byte[] d, RequestContext ctx) { data.put(k, d); }
    public byte[] get(String k, RequestContext ctx)           { return data.get(k); }
    public void   delete(String k, RequestContext ctx)        { data.remove(k); }
    public int    size()                                      { return data.size(); }
}
