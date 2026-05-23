package com.agentframework.observability;

import io.micrometer.core.instrument.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production {@link EventSink} backed by a Micrometer {@link MeterRegistry}.
 *
 * <h3>Metrics emitted</h3>
 * <table>
 *   <caption>Metric catalogue</caption>
 *   <tr><th>Metric</th><th>Type</th><th>Tags</th></tr>
 *   <tr><td>{@link MetricNames#AGENT_EVENT_TOTAL}</td><td>Counter</td>
 *       <td>event_type, tenant_id</td></tr>
 *   <tr><td>{@link MetricNames#AGENT_CYCLE_LATENCY_MS}</td><td>DistributionSummary</td>
 *       <td>tenant_id</td></tr>
 *   <tr><td>{@link MetricNames#AGENT_TOKENS_PER_CYCLE}</td><td>DistributionSummary</td>
 *       <td>tenant_id</td></tr>
 *   <tr><td>{@link MetricNames#AGENT_HOSTILE_TAINTS}</td><td>Counter</td>
 *       <td>severity, tenant_id</td></tr>
 *   <tr><td>{@link MetricNames#AGENT_HITL_SUSPENSIONS}</td><td>Counter</td>
 *       <td>tenant_id</td></tr>
 *   <tr><td>{@link MetricNames#AGENT_RUNS_ACTIVE}</td><td>Gauge (net)</td>
 *       <td>tenant_id</td></tr>
 * </table>
 *
 * <p>The {@link MeterRegistry} is constructor-injected; never obtained from
 * {@code Metrics.globalRegistry} to preserve per-test and per-tenant isolation.
 */
public class MicrometerEventSink implements EventSink {

    private final MeterRegistry registry;
    // Tracks per-tenant active run counts for the gauge metric
    private final ConcurrentHashMap<String, AtomicInteger> activeRuns =
        new ConcurrentHashMap<>();
    // Tracks cycle start time (by runId) for latency computation
    private final ConcurrentHashMap<String, Long> cycleStartNs =
        new ConcurrentHashMap<>();

    public MicrometerEventSink(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void emit(AgentEvent event) {
        String tenantId  = event.tenantId()  != null ? event.tenantId()  : "unknown";
        String eventType = event.eventType() != null ? event.eventType().name() : "UNKNOWN";
        String runId     = event.runId()     != null ? event.runId()     : "unknown";
        Map<String, Object> attrs = event.attributes() != null
            ? event.attributes() : Map.of();

        // General event counter — every event increments this
        Counter.builder(MetricNames.AGENT_EVENT_TOTAL)
            .tags("event_type", eventType, "tenant_id", tenantId)
            .register(registry)
            .increment();

        // Type-specific metrics
        switch (event.eventType()) {

            case RUN_STARTED -> {
                activeRuns.computeIfAbsent(tenantId,
                    k -> new AtomicInteger(0)).incrementAndGet();
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

            default -> {} // All other types are covered by the general counter above
        }
    }

    /** Simple thread-safe integer holder for gauge tracking. */
    private static final class AtomicInteger {
        private volatile int value;
        AtomicInteger(int v) { this.value = v; }
        synchronized void incrementAndGet() { value++; }
        synchronized void decrementAndGet() { if (value > 0) value--; }
        int get() { return value; }
    }
}
