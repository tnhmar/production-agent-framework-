package com.agentframework.core;
public record RequestContext(String tenantId, String userId) {
    public static RequestContext of(String t, String u) { return new RequestContext(t, u); }
    public static RequestContext system()               { return new RequestContext("system","system"); }
}
