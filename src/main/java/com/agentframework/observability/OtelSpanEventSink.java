package com.agentframework.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Decorator {@link EventSink} that wraps a delegate and creates an
 * OpenTelemetry span for every agent event.
 *
 * <p>Note: {@link AgentEvent} is a Java record; the event-type accessor is
 * {@code event.type()}, NOT {@code event.eventType()}.
 */
public class OtelSpanEventSink implements EventSink {

    private final EventSink delegate;
    private final Tracer    tracer;

    public OtelSpanEventSink(EventSink delegate, Tracer tracer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.tracer   = Objects.requireNonNull(tracer,   "tracer must not be null");
    }

    @Override
    public void emit(AgentEvent event) {
        // AgentEvent record field is 'type', not 'eventType'
        String spanName = "agent." + toSnakeCase(event.type());
        Span span = tracer.spanBuilder(spanName).startSpan();
        try {
            span.setAttribute("agent.run_id",    event.runId()    != null ? event.runId()    : "");
            span.setAttribute("agent.tenant_id", event.tenantId() != null ? event.tenantId() : "");

            Map<String, Object> attrs = event.attributes();
            if (attrs != null) {
                Object severity = attrs.get("severity");
                if ("ERROR".equals(severity) || "BLOCK".equals(severity)) {
                    span.setStatus(StatusCode.ERROR, severity.toString());
                }
            }

            delegate.emit(event);

        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    private static String toSnakeCase(AgentEvent.EventType type) {
        if (type == null) return "unknown";
        return type.name().toLowerCase(Locale.ROOT).replace('_', '.');
    }
}
