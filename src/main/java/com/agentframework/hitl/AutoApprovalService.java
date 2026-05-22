package com.agentframework.hitl;
/** Test/dev service that auto-approves all requests. */
public class AutoApprovalService implements ApprovalService {
    public ApprovalDecision awaitDecision(ApprovalPacket p) {
        return new ApprovalDecision.Approved();
    }
}
