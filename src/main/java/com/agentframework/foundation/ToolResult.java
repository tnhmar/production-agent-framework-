package com.agentframework.foundation;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
public record ToolResult(
        Object data,
        List<SideEffect> sideEffects,
        int tokensUsed,
        BigDecimal cost,
        Duration duration) {
    public boolean indicatesWorldChange() {
        return sideEffects.stream().anyMatch(e -> e != SideEffect.READ_ONLY);
    }
    public static ToolResult ok(Object data) {
        return new ToolResult(data, List.of(SideEffect.READ_ONLY), 0, BigDecimal.ZERO, Duration.ZERO);
    }
    public static ToolResult write(Object data) {
        return new ToolResult(data, List.of(SideEffect.WRITE_NON_IDEMPOTENT), 0, BigDecimal.ZERO, Duration.ZERO);
    }
    public static ToolResult rejected(String reason) {
        return new ToolResult("REJECTED:" + reason, List.of(), 0, BigDecimal.ZERO, Duration.ZERO);
    }
}
