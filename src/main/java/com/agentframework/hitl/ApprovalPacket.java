package com.agentframework.hitl;
import java.time.Duration; import java.time.Instant; import java.util.Map;
public record ApprovalPacket(
        String approvalId, String runId, String agentName, int stepNumber,
        String proposedAction, Map<String,Object> actionDetails,
        String reason, Map<String,Object> evidence,
        RiskClassification risk, String rollbackInfo,
        Instant createdAt, Duration timeToLive) {}
