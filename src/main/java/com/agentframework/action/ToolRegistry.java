package com.agentframework.action;
import com.agentframework.foundation.JsonSchema;
import java.time.Instant;
import java.util.*;
public interface ToolRegistry {
    void register(ToolContract contract, ToolHandler handler);
    void deregister(String name);
    ToolContract lookup(String name);
    List<ToolContract> list(ToolFilter filter);
    Optional<JsonSchema> schemaFor(String name);
    List<ToolContract> topK(String query, int k);
    void registerWithAlias(String alias, String canonicalName, String version);
    List<ToolContract> listVersions(String name);
    void deprecate(String name, String version, Instant sunsetDate);
    void remove(String name, String version);
    ToolHandler findHandler(String name);
}
