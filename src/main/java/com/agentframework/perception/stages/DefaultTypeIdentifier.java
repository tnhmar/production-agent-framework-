package com.agentframework.perception.stages;
public class DefaultTypeIdentifier implements TypeIdentifier {
    public InputType identify(Object raw) {
        if (raw instanceof String s) return s.trim().startsWith("{") ? InputType.JSON : InputType.TEXT;
        return InputType.UNKNOWN;
    }
}
