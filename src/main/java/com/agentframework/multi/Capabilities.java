package com.agentframework.multi;
public record Capabilities(boolean streaming, boolean pushNotifications, boolean stateful) {
    public static Capabilities basic() { return new Capabilities(false, false, true); }
}
