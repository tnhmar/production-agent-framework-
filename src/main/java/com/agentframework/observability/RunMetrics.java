package com.agentframework.observability;
import com.agentframework.core.ExecutionContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.*;
public class RunMetrics {
    private final String runId;
    private final Instant startTime;
    private final AtomicInteger toolCalls    = new AtomicInteger();
    private final AtomicInteger toolFailures = new AtomicInteger();

    public RunMetrics(String runId) { this.runId=runId; startTime=Instant.now(); }

    public void recordToolCall()    { toolCalls.incrementAndGet(); }
    public void recordToolFailure() { toolFailures.incrementAndGet(); }

    public MetricsSnapshot snapshot(ExecutionContext ctx) {
        long ms = Duration.between(startTime, Instant.now()).toMillis();
        return new MetricsSnapshot(runId, ctx.cycleCount(), ctx.totalTokensUsed(),
            ctx.totalCost(), ms, toolCalls.get(), toolFailures.get());
    }
}
