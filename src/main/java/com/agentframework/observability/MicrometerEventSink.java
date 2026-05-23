package com.agentframework.observability;

import io.micrometer.core.instrument.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production {@link EventSink} backed by a Micrometer {@link MeterRegistry}.
 *
 * <h3>Metrics emitted</h3>
 * <ul>
 *   <li>{@link MetricNames#AGENT_EVENT_TOTAL} — Counter (event_type, tenant_id)</li>
 *   <li>{@link MetricNames#AGENT_CYCLE_LATENCY_MS} — DistributionSummary (tenant_id)</li>
 *   <li>{@link MetricNames#AGENT_TOKENS_PER_CYCLE} — DistributionSummary (tenant_id)</li>
 *   <li>{@link MetricNames#AGENT_HOSTILE_TAINTS} — Counter (severity, tenant_id)</li>
 *   <li>{@link MetricNames#AGENT_HITL_SUSPENSIONS} — Counter (tenant_id)</li>
 *   <li>{@link MetricNames#AGENT_RUNS_ACTIVE} — Gauge (tenant_id)</li>
 * </ul>
 *
 * <p>Note: {@link AgentEvent} is a Java record; the event-type accessor is
 * {@code event.type()}, NOT {@code event.eventType()}.
 */
public class MicrometerEventSink implements EventSink {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, AtomicInteger> activeRuns =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cycleStartNs =
        new ConcurrentHashMap<>();

    public MicrometerEventSink(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void emit(AgentEvent event) {
        // AgentEvent record field is 'type', not 'eventType'
        String tenantId  = event.tenantId() != null ? event.tenantId() : "unknown";
        String eventType = event.type()     != null ? event.type().name() : "UNKNOWN";
        String runId     = event.runId()    != null ? event.runId()     : "unknown";
        Map<String, Object> attrs = event.attributes() != null
            ? event.attributes() : Map.of();

        Counter.builder(MetricNames.AGENT_EVENT_TOTAL)
            .tags("event_type", eventType, "tenant_id", tenantId)
            .register(registry)
            .increment();

        switch (event.type()) {

            case RUN_STARTED -> {
                activeRuns.computeIfAbsent(tenantId, k -> new AtomicInteger(0)).incrementAndGet();
                Gauge.builder(MetricNames.AGENT_RUNS_ACTIVE,
                    activeRuns.computeIfAbsent(tenantId, k -> new AtomicInteger(0)),
                    AtomicInteger::get)
                    .tags("tenant_id", tenantId)
                    .register(registry);
            }

            case RUN_COMPLETED, RUN_ABORTED -> {
                AtomicInteger count = activeRuns.get(tenantId);
                if (count != null) count.decrementAndGet();
            }

            case CYCLE_STARTED -> cycleStartNs.put(runId, System.nanoTime());

            case CYCLE_COMPLETED -> {
                Long startNs = cycleStartNs.remove(runId);
                if (startNs != null) {
                    long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
                    DistributionSummary.builder(MetricNames.AGENT_CYCLE_LATENCY_MS)
                        .baseUnit("milliseconds")
                        .tags("tenant_id", tenantId)
                        .register(registry)
                        .record(latencyMs);
                }
                Object tokens = attrs.get("tokens");
                if (tokens instanceof Number n) {
                    DistributionSummary.builder(MetricNames.AGENT_TOKENS_PER_CYCLE)
                        .baseUnit("tokens")
                        .tags("tenant_id", tenantId)
                        .register(registry)
                        .record(n.doubleValue());
                }
            }

            case HOSTILE_TAINT_DETECTED -> {
                String severity = attrs.getOrDefault("severity", "UNKNOWN").toString();
                Counter.builder(MetricNames.AGENT_HOSTILE_TAINTS)
                    .tags("severity", severity, "tenant_id", tenantId)
                    .register(registry)
                    .increment();
            }

            case HITL_REQUESTED ->
                Counter.builder(MetricNames.AGENT_HITL_SUSPENSIONS)
                    .tags("tenant_id", tenantId)
                    .register(registry)
                    .increment();

            default -> {}
        }
    }

    private static final class AtomicInteger {
        private volatile int value;
        AtomicInteger(int v) { this.value = v; }
        synchronized void incrementAndGet() { value++; }
        synchronized void decrementAndGet() { if (value > 0) value--; }
        int get() { return value; }
    }
}
