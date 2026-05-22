package com.agentframework.action;
import java.util.Set;
public record ToolFilter(Set<String> names, Set<ToolContract.SideEffectClass> sideEffects) {
    public static ToolFilter all() { return new ToolFilter(null, null); }
    public boolean matches(ToolContract c) {
        return (names == null || names.contains(c.name())) &&
               (sideEffects == null || sideEffects.contains(c.sideEffect()));
    }
}
