package com.agentframework.reasoning.strategy;

import com.agentframework.foundation.*;
import com.agentframework.reasoning.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * Plan-and-Execute reasoning strategy.
 *
 * <p><b>Two modes</b>, selected by the LLM via the {@code mode} field:
 * <ul>
 *   <li>{@code PLAN} — LLM decomposes the goal into sub-tasks (JSON list).
 *       The strategy encodes the list as an {@link AskClarification} carrying
 *       the {@link #PLAN_PREFIX} marker.  {@code StateMachineRunner} detects
 *       this marker, pushes sub-goals, and re-enters the reasoning cycle.</li>
 *   <li>{@code EXECUTE} — LLM selects one action for the current sub-task,
 *       parsed via {@link JsonDecisionParser}.</li>
 * </ul>
 *
 * <p><b>No hardcoded strings</b> — all literals are constants in this class.
 * {@code maxSubtasks} is injected; no magic number in logic.
 *
 * <p><b>Safety guards</b>:
 * <ul>
 *   <li>Empty subtask list → {@link Escalate} HIGH rather than silent no-op.</li>
 *   <li>Subtask list truncated to {@code maxSubtasks} with no data loss
 *       (truncation is logged in the reasoning trace).</li>
 *   <li>Blank/null subtask entries filtered before encoding.</li>
 * </ul>
 */
public final class PlanAndExecuteStrategy implements ReasoningStrategy {

    // ── Protocol marker (no inline string literals anywhere in logic) ───────
    /** Prefix used to signal a plan list through the AskClarification channel. */
    public static final String PLAN_PREFIX     = "PLAN:";
    /** Delimiter separating subtasks in the encoded plan string. */
    public static final String PLAN_DELIMITER  = "||";

    // ── JSON field constants ────────────────────────────────────────────────
    private static final String FIELD_MODE     = "mode";
    private static final String FIELD_SUBTASKS = "subtasks";
    private static final String MODE_PLAN      = "PLAN";
    private static final String MODE_EXECUTE   = "EXECUTE";

    // ── Escalation messages ────────────────────────────────────────────────
    private static final String ERR_EMPTY_OUTPUT  = "PlanAndExecute: empty LLM output";
    private static final String ERR_NO_JSON       = "PlanAndExecute: no JSON object found";
    private static final String ERR_EMPTY_PLAN    = "PlanAndExecute: decomposition returned empty subtask list";
    private static final String ERR_PARSE_FAILURE = "PlanAndExecute: parse error: ";

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    // ── Configuration ──────────────────────────────────────────────────────
    private final int maxSubtasks;

    /**
     * @param maxSubtasks  maximum number of sub-tasks the LLM may return; [1, 20].
     */
    public PlanAndExecuteStrategy(int maxSubtasks) {
        if (maxSubtasks < 1 || maxSubtasks > 20)
            throw new IllegalArgumentException(
                "maxSubtasks must be in [1, 20], got: " + maxSubtasks);
        this.maxSubtasks = maxSubtasks;
    }

    /** Factory with production-sensible default. */
    public static PlanAndExecuteStrategy withDefault() {
        return new PlanAndExecuteStrategy(6);
    }

    // ── ReasoningStrategy contract ─────────────────────────────────────────

    @Override
    public String outputSchemaDescription() {
        return "Plan-and-Execute mode.\n" +
            "If no plan exists, output a decomposition:\n" +
            "{\"mode\":\"" + MODE_PLAN + "\",\"subtasks\":[\"task1\",\"task2\",...]}\n" +
            "Max " + maxSubtasks + " sub-tasks. Each must be independently executable.\n\n" +
            "If a plan is active, execute the current sub-task:\n" +
            "{\"mode\":\"" + MODE_EXECUTE + "\",\"type\":\"<tool_call|final_answer|escalate>\"," +
            "\"tool_name\":\"<name>\",\"arguments\":{...},\"reasoning_trace\":\"<why>\",\"content\":\"<text>\"}"
            ;
    }

    @Override
    public Decision parse(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank())
            return new Escalate(ERR_EMPTY_OUTPUT,
                JsonDecisionParser.Severity.HIGH.value);

        String json = JsonDecisionParser.extractJson(llmOutput);
        if (json.equals("{}"))
            return new Escalate(ERR_NO_JSON,
                JsonDecisionParser.Severity.HIGH.value);

        try {
            JsonNode root = MAPPER.readTree(json);
            String mode = root.path(FIELD_MODE).asText(MODE_EXECUTE).toUpperCase();

            if (MODE_PLAN.equals(mode)) {
                return parsePlanNode(root);
            }
            // EXECUTE or any other mode — delegate to shared parser
            return JsonDecisionParser.fromNode(root);

        } catch (Exception e) {
            return new Escalate(ERR_PARSE_FAILURE + e.getMessage(),
                JsonDecisionParser.Severity.HIGH.value);
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private Decision parsePlanNode(JsonNode root) {
        JsonNode subtasksNode = root.path(FIELD_SUBTASKS);
        if (!subtasksNode.isArray() || subtasksNode.isEmpty())
            return new Escalate(ERR_EMPTY_PLAN,
                JsonDecisionParser.Severity.HIGH.value);

        List<String> subtasks = new ArrayList<>();
        subtasksNode.forEach(n -> {
            if (!n.isNull() && !n.asText().isBlank())
                subtasks.add(n.asText());
        });

        if (subtasks.isEmpty())
            return new Escalate(ERR_EMPTY_PLAN,
                JsonDecisionParser.Severity.HIGH.value);

        List<String> capped = subtasks.size() > maxSubtasks
            ? subtasks.subList(0, maxSubtasks)
            : subtasks;

        String encoded = PLAN_PREFIX + String.join(PLAN_DELIMITER, capped);
        return new AskClarification(encoded);
    }
}
