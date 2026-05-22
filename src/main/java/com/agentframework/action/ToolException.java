package com.agentframework.action;
public class ToolException extends Exception {
    private final String errorCode;
    public ToolException(String code, String msg)           { super(msg); this.errorCode = code; }
    public ToolException(String code, String msg, Throwable cause){ super(msg,cause); this.errorCode=code; }
    public String errorCode() { return errorCode; }
}
