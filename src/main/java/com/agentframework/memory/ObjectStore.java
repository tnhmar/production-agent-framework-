package com.agentframework.memory;
import com.agentframework.core.RequestContext;
public interface ObjectStore {
    void   put(String key, byte[] data, RequestContext ctx);
    byte[] get(String key, RequestContext ctx);
    void   delete(String key, RequestContext ctx);
}
