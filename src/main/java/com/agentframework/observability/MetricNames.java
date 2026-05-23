package com.agentframework.observability;

/**
 * Central registry of Micrometer metric names.
 *
 * <p>All names follow the Prometheus convention: lowercase words separated by dots.
 * Changing a name here automatically propagates to all metric emission sites.
 */
public final class MetricNames {

    private MetricNames() { throw new AssertionError("Not instantiable"); }

    public static final String AGENT_EVENT_TOTAL       = "agent.event.total";
    public static final String AGENT_CYCLE_LATENCY_MS  = "agent.cycle.latency.ms";
    public static final String AGENT_TOKENS_PER_CYCLE  = "agent.tokens.per.cycle";
    public static final String AGENT_RETRIES_TOTAL      = "agent.retries.total";
    public static final String AGENT_TOOL_ERRORS_TOTAL  = "agent.tool.errors.total";
    public static final String AGENT_HOSTILE_TAINTS     = "agent.hostile.taints.total";
    public static final String AGENT_RUNS_ACTIVE        = "agent.runs.active";
    public static final String AGENT_HITL_SUSPENSIONS   = "agent.hitl.suspensions.total";
}
