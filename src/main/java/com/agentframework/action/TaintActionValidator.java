package com.agentframework.action;

import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.observability.*;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Blocks tool calls whose argument values are <em>derived from</em>
 * HOSTILE-tainted working-memory entries.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Collect the {@code id} values of all working-memory entries whose
 *       {@link TaintLabel} is {@link TaintLabel#HOSTILE}.</li>
 *   <li>For each argument value in the candidate tool call, check whether
 *       the string representation contains any hostile entry id. This is a
 *       lightweight data-flow proxy: prompt-injection attacks almost always
 *       include the injected content verbatim in subsequent tool arguments.</li>
 *   <li>If propagation is confirmed — block and emit a
 *       {@link AgentEvent.EventType#HOSTILE_TAINT_DETECTED} event.</li>
 *   <li>If HOSTILE entries exist but are NOT referenced in the arguments —
 *       pass the call and emit an {@link AgentEvent.EventType#HOSTILE_TAINT_DETECTED}
 *       audit event with severity WARN so operators remain informed without
 *       interrupting agent execution.</li>
 * </ol>
 *
 * <p>This class is intentionally free of static singletons.  The {@link EventSink}
 * is constructor-injected so the validator is fully testable without side effects.
 */
public class TaintActionValidator implements ActionValidator {

    private final EventSink events;

    public TaintActionValidator(EventSink events) {
        this.events = events;
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

        boolean taintPropagated = call.arguments().values().stream()
            .filter(v -> v != null)
            .anyMatch(v -> {
                String str = v.toString();
                return hostileIds.stream().anyMatch(str::contains);
            });

        if (taintPropagated) {
            events.emit(new AgentEvent(
                ctx.runId(), ctx.tenantId(),
                AgentEvent.EventType.HOSTILE_TAINT_DETECTED,
                Instant.now(),
                Map.of(
                    "severity",  "BLOCK",
                    "tool",      call.toolName(),
                    "hostileIds", hostileIds.toString())));
            return ValidationVerdict.failed(
                "Tool call '" + call.toolName() + "' blocked: argument contains "
                + "reference to HOSTILE-tainted working-memory entry.");
        }

        // HOSTILE entries present but not propagated — audit without blocking
        events.emit(new AgentEvent(
            ctx.runId(), ctx.tenantId(),
            AgentEvent.EventType.HOSTILE_TAINT_DETECTED,
            Instant.now(),
            Map.of(
                "severity",   "WARN",
                "tool",       call.toolName(),
                "hostileIds", hostileIds.toString(),
                "note",       "HOSTILE entries present but not referenced in tool arguments")));

        return ValidationVerdict.ok();
    }
}
