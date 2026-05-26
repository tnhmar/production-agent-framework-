package com.agentframework.reasoning.strategy;

import com.agentframework.foundation.Decision;
import com.agentframework.reasoning.LLMProvider;
import com.agentframework.reasoning.Prompt;
import com.agentframework.reasoning.ReasoningStrategy;

/**
 * ReAct (Reasoning + Acting) strategy.
 *
 * <h3>H2 fix — unified JSON parsing via {@link JsonDecisionParser}</h3>
 * <p>Previously this class carried its own private {@code ObjectMapper},
 * {@code extractJson()}, {@code text()}, {@code parseArgs()}, and
 * {@code firstNonNull()} helpers that differed subtly from
 * {@link JsonDecisionParser} (numeric type preservation, brace-depth fallback,
 * blank-string handling).  Those divergences could produce different
 * {@link Decision} objects from identical LLM output depending on which
 * strategy was in use.
 *
 * <p>{@link #parse(String)} is now a one-liner that delegates to
 * {@link JsonDecisionParser#parse(String)}, making all four strategy
 * implementations share exactly one parser.
 *
 * <p>Expected LLM output schema:
 * <pre>
 * {
 *   "type": "tool_call" | "final_answer" | "escalate" | "ask_clarification",
 *   "tool_name":       "&lt;name&gt;",   // required for tool_call
 *   "arguments":       { ... },      // required for tool_call
 *   "reasoning_trace": "&lt;why&gt;",
 *   "content":         "&lt;text&gt;" // required for final_answer / ask_clarification
 * }
 * </pre>
 */
public class ReActStrategy implements ReasoningStrategy {

    @Override
    public String outputSchemaDescription() {
        return """
            Output exactly one JSON object:
            {
              "type": "tool_call" | "final_answer" | "escalate" | "ask_clarification",
              "tool_name": "<name>",          // required for tool_call
              "arguments":  { ... },          // required for tool_call
              "reasoning_trace": "<why>",
              "content": "<text>"             // required for final_answer / ask_clarification
            }
            """;
    }

    /**
     * Parses raw LLM output into a {@link Decision}.
     * Delegates entirely to {@link JsonDecisionParser#parse(String)} (H2 fix).
     */
    @Override
    public Decision parse(String llmOutput) {
        return JsonDecisionParser.parse(llmOutput);
    }
}
