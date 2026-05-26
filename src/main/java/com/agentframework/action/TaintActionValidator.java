package com.agentframework.action;

import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.observability.*;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Blocks tool calls when HOSTILE-tainted working-memory entries are present.
 *
 * <h3>Algorithm (H3 fix)</h3>
 * <ol>
 *   <li>Collect the {@code id} values of all working-memory entries whose
 *       {@link TaintLabel} is {@link TaintLabel#HOSTILE}.</li>
 *   <li>If no HOSTILE entries exist — pass immediately.</li>
 *   <li>Check whether any argument value contains a hostile entry id verbatim
 *       (lightweight data-flow proxy for prompt-injection propagation).</li>
 *   <li><b>Either way — block unconditionally.</b> Two severity values
 *       distinguish cases for operator triage:
 *       <ul>
 *         <li>{@code BLOCK/PROPAGATED} — hostile id confirmed in arguments
 *             (data-flow confirmed)</li>
 *         <li>{@code BLOCK/UNCONFIRMED} — hostile entry exists but id not
 *             found in arguments (precautionary block)</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>H3 fix rationale:</b> the previous "pass with WARN audit" branch was
 * dead code in the default stack (SecurityEnforcer already blocked at position 3)
 * but represented a real security regression in custom stacks that omit
 * SecurityEnforcer.  The pass-through has been removed; any HOSTILE entry now
 * blocks unconditionally regardless of stack composition.
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

        boolean confirmed = call.arguments().values().stream()
            .filter(v -> v != null)
            .anyMatch(v -> {
                String str = v.toString();
                return hostileIds.stream().anyMatch(str::contains);
            });

        // H3 fix: block unconditionally — no silent pass-through.
        // Severity distinguishes confirmed data-flow from precautionary blocks.
        String severity = confirmed ? "BLOCK/PROPAGATED" : "BLOCK/UNCONFIRMED";
        String detail   = confirmed
            ? "argument contains reference to HOSTILE-tainted working-memory entry"
            : "HOSTILE working-memory entry present (id not found verbatim in arguments — precautionary block)";

        events.emit(new AgentEvent(
            ctx.runId(), ctx.tenantId(),
            AgentEvent.EventType.HOSTILE_TAINT_DETECTED,
            Instant.now(),
            Map.of(
                "severity",   severity,
                "tool",       call.toolName(),
                "hostileIds", hostileIds.toString())));

        return ValidationVerdict.failed(
            "Tool call '" + call.toolName() + "' blocked: " + detail);
    }
}
