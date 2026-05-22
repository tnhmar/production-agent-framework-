package com.agentframework.hitl;
import com.agentframework.foundation.ToolCall;
public sealed interface ApprovalDecision
    permits ApprovalDecision.Approved, ApprovalDecision.Rejected,
            ApprovalDecision.Modified,  ApprovalDecision.Escalated {
    record Approved()                    implements ApprovalDecision {}
    record Rejected(String reason)       implements ApprovalDecision {}
    record Modified(ToolCall updatedCall)implements ApprovalDecision {}
    record Escalated(String reason)      implements ApprovalDecision {}
}
