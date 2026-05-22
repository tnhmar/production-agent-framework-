package com.agentframework.multi;
public interface A2AClient {
    A2ATask sendTask(TaskSpec spec);
    A2ATask checkStatus(String taskId);
}
