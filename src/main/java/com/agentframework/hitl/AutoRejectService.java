package com.agentframework.hitl;
/** Test service that auto-rejects all requests. */
public class AutoRejectService implements ApprovalService {
    public ApprovalDecision awaitDecision(ApprovalPacket p) {
        return new ApprovalDecision.Rejected("auto-rejected in test");
    }
}
