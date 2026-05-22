package com.agentframework.multi;
public record A2ATask(String taskId, String state, Object result, String error) {
    public boolean isComplete() { return "completed".equals(state) || "failed".equals(state); }
    public boolean isSuccess()  { return "completed".equals(state); }
}
