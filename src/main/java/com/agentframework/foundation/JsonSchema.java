package com.agentframework.foundation;
public record JsonSchema(String value) {
    public static JsonSchema of(String v) { return new JsonSchema(v); }
    @Override public String toString()    { return value; }
}
