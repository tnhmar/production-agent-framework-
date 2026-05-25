package com.agentframework.reasoning.strategy;

import com.agentframework.foundation.*;
import com.agentframework.reasoning.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * Reflexion reasoning strategy.
 *
 * <p>After each tool execution the LLM critiques its own result and
 * decides whether to PROCEED, RETRY, REVISE, or ANSWER.  Two-phase
 * execution is preserved via the output schema: a single JSON object
 * with both a {@code critique} sub-object and an {@code action} sub-object.
 *
 * <p><b>RETRY signal</b>: encoded as {@link AskClarification} carrying
 * {@link #RETRY_MARKER}.  {@code StateMachineRunner} detects this marker
 * and resubmits the last {@link ToolCall} from the most recent
 * {@code CycleRecord} rather than interpreting it as a user question.
 *
 * <p><b>No hardcoded strings</b> — all literals are constants in this class.
 *
 * <p><b>Safety guard</b>: missing or malformed {@code critique} node
 * defaults to {@link NextAction#PROCEED} rather than throwing, ensuring
 * the strategy degrades gracefully under partial LLM output.
 *
 * <p>Hard ceiling on RETRY/REVISE cycles is enforced externally by
 * {@code StateMachineRunner.isRevisionBudgetExceeded()} — Reflexion
 * cannot loop indefinitely regardless of LLM output.
 */
public final class ReflexionStrategy implements ReasoningStrategy {

    // ── Protocol marker ────────────────────────────────────────────────────
    /** Carried by AskClarification to signal a RETRY request to StateMachineRunner. */
    public static final String RETRY_MARKER = "REFLEXION_RETRY";

    // ── JSON field constants ────────────────────────────────────────────────
    private static final String FIELD_CRITIQUE          = "critique";
    private static final String FIELD_ACTION            = "action";
    private static final String FIELD_NEXT              = "next";
    private static final String FIELD_QUALITY           = "quality";
    private static final String FIELD_REASON            = "reason";

    // ── NextAction enum — no inline string literals in switch ──────────────
    private enum NextAction {
        PROCEED, RETRY, REVISE, ANSWER;

        static NextAction from(String v) {
            if (v == null) return PROCEED;
            try { return valueOf(v.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { return PROCEED; }
        }
    }

    // ── Escalation / fallback messages ─────────────────────────────────────
    private static final String ERR_EMPTY_OUTPUT     = "Reflexion: empty LLM output";
    private static final String ERR_NO_JSON          = "Reflexion: no JSON object found";
    private static final String ERR_EMPTY_ANSWER     = "Reflexion: ANSWER requested but action.content is empty";
    private static final String ERR_PARSE_FAILURE    = "Reflexion: parse error: ";
    private static final String DEFAULT_EMPTY_CONTENT = "";

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    // ── ReasoningStrategy contract ─────────────────────────────────────────

    @Override
    public String outputSchemaDescription() {
        return "Reflexion mode.\n" +
            "Respond with a single JSON object:\n" +
            "{\n" +
            "  \"critique\": {\n" +
            "    \"quality\": \"SUFFICIENT|INSUFFICIENT|WRONG\",\n" +
            "    \"reason\": \"<max 2 sentences>\",\n" +
            "    \"next\": \"PROCEED|RETRY|REVISE|ANSWER\"\n" +
            "  },\n" +
            "  \"action\": {\n" +
            "    \"type\": \"tool_call|final_answer|escalate\",\n" +
            "    \"tool_name\": \"<name>\",\n" +
            "    \"arguments\": { ... },\n" +
            "    \"reasoning_trace\": \"<why>\",\n" +
            "    \"content\": \"<text for final_answer>\"\n" +
            "  }\n" +
            "}\n" +
            "If no prior result exists, omit the critique block.\n" +
            "next rules: ANSWER=goal satisfied; PROCEED=good result, more steps; " +
            "REVISE=wrong args/partial; RETRY=transient failure.";
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

            // ── Derive next action from critique (defaults to PROCEED if absent) ──
            JsonNode critiqueNode = root.path(FIELD_CRITIQUE);
            NextAction next = NextAction.PROCEED;
            if (!critiqueNode.isMissingNode() && !critiqueNode.isNull()) {
                next = NextAction.from(
                    critiqueNode.path(FIELD_NEXT).asText(null));
            }

            return switch (next) {
                case RETRY  -> new AskClarification(RETRY_MARKER);
                case ANSWER -> parseAnswerFromAction(root.path(FIELD_ACTION));
                case PROCEED,
                     REVISE -> JsonDecisionParser.fromNode(root.path(FIELD_ACTION));
            };

        } catch (Exception e) {
            return new Escalate(ERR_PARSE_FAILURE + e.getMessage(),
                JsonDecisionParser.Severity.HIGH.value);
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private Decision parseAnswerFromAction(JsonNode actionNode) {
        if (actionNode.isMissingNode() || actionNode.isNull())
            return new Escalate(ERR_EMPTY_ANSWER,
                JsonDecisionParser.Severity.HIGH.value);
        String content = actionNode.path("content").asText(DEFAULT_EMPTY_CONTENT);
        if (content.isBlank())
            return new Escalate(ERR_EMPTY_ANSWER,
                JsonDecisionParser.Severity.HIGH.value);
        return new FinalAnswer(content, List.of());
    }
}
