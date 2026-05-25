package com.agentframework.integration.llm;

import com.agentframework.reasoning.Message.Role;

/**
 * Shared utility for mapping domain {@link Role} constants to provider-specific
 * string values. Centralises the mapping so adding a new Role constant
 * requires a change in exactly one place.
 */
public final class RoleMapper {

    private RoleMapper() {}

    /**
     * Maps to the OpenAI / Mistral / Ollama role string.
     * These three providers share the same role vocabulary.
     */
    public static String toOpenAiStyle(Role role) {
        return switch (role) {
            case SYSTEM      -> "system";
            case USER        -> "user";
            case ASSISTANT   -> "assistant";
            case TOOL_RESULT -> "tool";
        };
    }

    /**
     * Maps to the Anthropic role string.
     * SYSTEM messages must be lifted to the top-level system field before
     * this method is called; passing SYSTEM here is a programming error.
     */
    public static String toAnthropicStyle(Role role) {
        return switch (role) {
            case USER        -> "user";
            case ASSISTANT   -> "assistant";
            case TOOL_RESULT -> "user";   // Anthropic wraps tool results as user turns
            case SYSTEM      -> throw new IllegalStateException(
                    "SYSTEM messages must be lifted to the top-level system field "
                            + "before calling toAnthropicStyle");
        };
    }

    /**
     * Maps to the Gemini 'role' field inside a content part.
     * SYSTEM is not a valid Gemini content role — must be handled separately
     * via systemInstruction.
     */
    public static String toGeminiStyle(Role role) {
        return switch (role) {
            case USER        -> "user";
            case ASSISTANT   -> "model";
            case TOOL_RESULT -> "user";
            case SYSTEM      -> throw new IllegalStateException(
                    "SYSTEM messages must be placed in systemInstruction, "
                            + "not in contents[]");
        };
    }
}
