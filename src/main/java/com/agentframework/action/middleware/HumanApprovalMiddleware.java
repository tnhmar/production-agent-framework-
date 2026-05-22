package com.agentframework.action.middleware;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.hitl.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
public class HumanApprovalMiddleware implements ToolMiddleware {
    private final ApprovalService  approvalService;
    private final ExecutionStore   executionStore;
    public HumanApprovalMiddleware(ApprovalService a, ExecutionStore e){
        approvalService=a; executionStore=e;}
    public ToolResult apply(ToolInvocation inv, Function<ToolInvocation, ToolResult> next) {
        if (!inv.validationVerdict().requiresApproval()) return next.apply(inv);
        ExecutionContext ctx = inv.ctx();
        ctx.transitionTo(RunState.SUSPENDED_HITL);
        executionStore.save(ctx.checkpoint());
        ApprovalPacket packet = new ApprovalPacket(
            UUID.randomUUID().toString(), ctx.runId(), "agent", ctx.cycleCount(),
            inv.contract().name(), inv.arguments(), "tool call", Map.of(),
            RiskClassification.MEDIUM, "rollback", Instant.now(), Duration.ofHours(1));
        ApprovalDecision decision = approvalService.awaitDecision(packet);
        return switch (decision) {
            case ApprovalDecision.Approved a -> { ctx.transitionTo(RunState.TOOL_EXECUTION); yield next.apply(inv); }
            case ApprovalDecision.Modified m -> {
                ToolInvocation updated = new ToolInvocation(
                    inv.contract(), m.updatedCall().arguments(), ctx, inv.validationVerdict());
                ctx.transitionTo(RunState.TOOL_EXECUTION);
                yield next.apply(updated);
            }
            default -> ToolResult.rejected(decision.toString());
        };
    }
}
