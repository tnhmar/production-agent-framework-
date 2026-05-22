package com.agentframework.hitl;
public interface ApprovalService {
    ApprovalDecision awaitDecision(ApprovalPacket packet) throws ApprovalTimeoutException;
}
