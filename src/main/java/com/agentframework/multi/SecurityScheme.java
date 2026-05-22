package com.agentframework.multi;
public record SecurityScheme(String type, String issuer, String jwksUrl) {
    public static SecurityScheme none() { return new SecurityScheme("none", "", ""); }
}
