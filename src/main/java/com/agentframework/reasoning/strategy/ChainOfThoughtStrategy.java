package com.agentframework.reasoning.strategy;

import com.agentframework.foundation.Decision;
import com.agentframework.reasoning.*;
import java.util.*;
import java.util.Objects;

/**
 * Chain-of-Thought (CoT) reasoning strategy.
 *
 * <p><b>Two-phase execution</b>:
 * <ol>
 *   <li>Phase 1 — reasoning-only call: no tool schema injected, token-capped
 *       by {@code maxReasoningTokens}.  The raw reasoning text is captured.</li>
 *   <li>Phase 2 — action call: reasoning injected as an ASSISTANT turn so
 *       the LLM treats it as its own prior output; then selects one action
 *       from the full tool schema.</li>
 * </ol>
 *
 * <p><b>No hardcoded strings</b> — all message content constants are declared
 * as {@code private static final} fields in this class.
 *
 * <p><b>OCP compliance</b>: implements {@link ReasoningStrategy} only;
 * all JSON parsing delegated to {@link JsonDecisionParser}.
 */
public final class ChainOfThoughtStrategy implements ReasoningStrategy {

    // ── Prompt content constants — no inline string literals ───────────────
    private static final String PHASE1_USER_SUFFIX =
        "\n\nThink step-by-step about the goal above. " +
        "Do NOT select a tool yet. " +
        "End your response with: PLAN: <one concrete action sentence>";

    private static final String CHAIN_OF_THOUGHT_LABEL = "CHAIN_OF_THOUGHT:\n";

    private static final String PHASE2_USER_SUFFIX =
        "\n\nUsing the chain-of-thought reasoning above, select the single best " +
        "next action. Respond using the output schema below.\n";

    // ── Configuration ──────────────────────────────────────────────────────
    private final int maxReasoningTokens;

    /**
     * @param maxReasoningTokens  token cap for phase-1 reasoning call;
     *                             must be in range [64, 4096].
     */
    public ChainOfThoughtStrategy(int maxReasoningTokens) {
        if (maxReasoningTokens < 64 || maxReasoningTokens > 4096)
            throw new IllegalArgumentException(
                "maxReasoningTokens must be in [64, 4096], got: " + maxReasoningTokens);
        this.maxReasoningTokens = maxReasoningTokens;
    }

    /** Factory with production-sensible default (512 tokens for reasoning phase). */
    public static ChainOfThoughtStrategy withDefault() {
        return new ChainOfThoughtStrategy(512);
    }

    // ── ReasoningStrategy contract ─────────────────────────────────────────

    @Override
    public String outputSchemaDescription() {
        return JsonDecisionParser.DecisionType.TOOL_CALL.jsonValue +
            " | " + JsonDecisionParser.DecisionType.FINAL_ANSWER.jsonValue +
            " | " + JsonDecisionParser.DecisionType.ESCALATE.jsonValue +
            " | " + JsonDecisionParser.DecisionType.ASK_CLARIFICATION.jsonValue +
            "\nOutput exactly one JSON object matching the schema:\n" +
            "{\n" +
            "  \"type\": \"<one of the types above>\",\n" +
            "  \"tool_name\": \"<name>\",\n" +
            "  \"arguments\": { ... },\n" +
            "  \"reasoning_trace\": \"<why>\",\n" +
            "  \"content\": \"<text for final_answer / ask_clarification>\"\n" +
            "}";
    }

    /**
     * Two-phase decide: phase-1 raw reasoning, phase-2 action selection.
     * Uses only {@link LLMProvider#generate(Prompt)} — no phantom methods.
     */
    @Override
    public Decision decide(LLMProvider llm, Prompt prompt) {
        Objects.requireNonNull(llm,    "llm must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");

        // ── Phase 1: reasoning-only, token-capped ─────────────────────────
        List<Message> phase1Messages = new ArrayList<>(prompt.messages());
        phase1Messages.add(new Message(Message.Role.USER, PHASE1_USER_SUFFIX));

        Prompt phase1Prompt = new Prompt(
            Collections.unmodifiableList(phase1Messages),
            prompt.parameters().withMaxTokens(maxReasoningTokens));

        String reasoning = llm.generate(phase1Prompt);
        if (reasoning == null) reasoning = "";

        // ── Phase 2: inject reasoning as ASSISTANT turn, request action ───
        List<Message> phase2Messages = new ArrayList<>(prompt.messages());
        phase2Messages.add(new Message(Message.Role.ASSISTANT,
            CHAIN_OF_THOUGHT_LABEL + reasoning));
        phase2Messages.add(new Message(Message.Role.USER,
            PHASE2_USER_SUFFIX + outputSchemaDescription()));

        Prompt phase2Prompt = new Prompt(
            Collections.unmodifiableList(phase2Messages),
            prompt.parameters());

        return parse(llm.generate(phase2Prompt));
    }

    @Override
    public Decision parse(String llmOutput) {
        return JsonDecisionParser.parse(llmOutput);
    }
}
