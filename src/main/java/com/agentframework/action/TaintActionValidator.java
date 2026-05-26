package com.agentframework.action;

import com.agentframework.action.middleware.ValidationVerdict;
import com.agentframework.core.ExecutionContext;
import com.agentframework.core.WorkingMemoryEntry;
import com.agentframework.foundation.TaintLabel;
import com.agentframework.foundation.ToolCall;
import com.agentframework.observability.AgentEvent;
import com.agentframework.observability.EventSink;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a {@link ToolCall} against the taint labels held in working memory.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Collect the {@code id} values of all working-memory entries whose
 *       {@link TaintLabel} is {@link TaintLabel#HOSTILE}.</li>
 *   <li>If no HOSTILE entries exist — pass immediately, no event.</li>
 *   <li>Check whether any argument value contains a hostile entry id verbatim
 *       (lightweight data-flow proxy for prompt-injection propagation).</li>
 *   <li>If a hostile id <b>is</b> referenced in the arguments — block the call
 *       ({@code isPassed() == false}) and emit a {@code BLOCK} severity event.</li>
 *   <li>If hostile entries exist but <b>none</b> of their ids appear in the
 *       arguments — pass the call and emit a {@code WARN} severity audit event
 *       so operators have full traceability.</li>
 * </ol>
 *
 * <p>The {@link EventSink} is constructor-injected so the validator is fully
 * testable without side effects.  Thread-safe: all state is passed via
 * parameters; the sink must itself be thread-safe.
 */
public class TaintActionValidator implements ActionValidator {

    // ── Severity literals — must match MiddlewareCoverageTest assertions ──
    private static final String SEVERITY_BLOCK = "BLOCK";
    private static final String SEVERITY_WARN  = "WARN";

    // ── Event attribute keys ─────────────────────────────────────
    private static final String ATTR_SEVERITY  = "severity";
    private static final String ATTR_TOOL      = "tool";
    private static final String ATTR_HOSTILE   = "hostileIds";

    private final EventSink events;

    public TaintActionValidator(EventSink events) {
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    @Override
    public ValidationVerdict validate(ToolCall call, ToolContract contract, ExecutionContext ctx) {
        Set<String> hostileIds = ctx.workingMemory().getAll().stream()
            .filter(e -> e.taintLabel() == TaintLabel.HOSTILE)
            .map(WorkingMemoryEntry::id)
            .collect(Collectors.toSet());

        if (hostileIds.isEmpty()) {
            return ValidationVerdict.ok();
        }

        boolean referenced = call.arguments() != null
            && call.arguments().values().stream()
                   .filter(Objects::nonNull)
                   .anyMatch(v -> hostileIds.stream().anyMatch(v.toString()::contains));

        if (referenced) {
            emit(call.toolName(), ctx, SEVERITY_BLOCK, hostileIds);
            return ValidationVerdict.failed(
                "Tool call '" + call.toolName()
                    + "' blocked: argument references HOSTILE-tainted working-memory entry");
        } else {
            emit(call.toolName(), ctx, SEVERITY_WARN, hostileIds);
            return ValidationVerdict.ok();
        }
    }

    private void emit(String toolName, ExecutionContext ctx,
                      String severity, Set<String> hostileIds) {
        events.emit(new AgentEvent(
            ctx.runId(), ctx.tenantId(),
            AgentEvent.EventType.HOSTILE_TAINT_DETECTED,
            Instant.now(),
            Map.of(
                ATTR_SEVERITY, severity,
                ATTR_TOOL,     toolName,
                ATTR_HOSTILE,  hostileIds.toString())));
    }
}
