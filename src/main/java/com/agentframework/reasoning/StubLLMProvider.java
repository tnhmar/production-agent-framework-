package com.agentframework.reasoning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Deterministic, multi-turn LLM stub for testing.
 *
 * <p>Replaces the original single-response {@code StubLLMProvider} with a
 * dispatch model that covers every {@link StateMachineRunner} cycle path:
 *
 * <ul>
 *   <li><b>Sequential script</b> — responses consumed one by one; the last
 *       entry repeats on exhaustion so the run always receives a valid string.</li>
 *   <li><b>Call-index routes</b> — override the script for an exact call index
 *       (0-based). Useful for injecting a {@code HITL_REQUIRED} or a malformed
 *       decision at a specific cycle.</li>
 *   <li><b>Prompt-content routes</b> — override when the last USER message
 *       contains a given keyword. Useful for reacting to tool results or
 *       staleness hints that {@link PromptBuilder} injects.</li>
 * </ul>
 *
 * <p>Dispatch order per call:
 * <ol>
 *   <li>Call-index routes, checked in insertion order.</li>
 *   <li>Prompt-content routes, checked in insertion order.</li>
 *   <li>Sequential script (pointer advances; clamps at last entry).</li>
 *   <li>Built-in fallback: MEDIUM-severity {@code escalate} JSON — matches
 *       the severity that
 *       {@link com.agentframework.reasoning.strategy.JsonDecisionParser}
 *       actually produces on an {@code escalate} node so test assertions
 *       are not surprised.</li>
 * </ol>
 *
 * <p><b>Backward compatibility</b>: the three original static factories
 * ({@link #finalAnswer}, {@link #toolCall}, {@link #escalate}) are preserved
 * unchanged so existing single-step tests compile without modification.
 *
 * <p><b>Thread safety</b>: not thread-safe — one instance per test.
 */
public final class StubLLMProvider implements LLMProvider {

    // ── Fallback JSON ─────────────────────────────────────────────────────
    // JsonDecisionParser.fromNode() always produces Severity.MEDIUM for an
    // "escalate" node — the "severity" field is never read from the JSON
    // payload. It is therefore absent here and in every escalate helper below.
    private static final String FALLBACK_JSON =
        "{\"type\":\"escalate\","
        + "\"reasoning_trace\":\"stub-exhausted \u2014 no script entry for this call\"}";

    // ── Routes ────────────────────────────────────────────────────────────

    private final List<Route> indexRoutes   = new ArrayList<>();
    private final List<Route> contentRoutes = new ArrayList<>();

    // ── Sequential script ─────────────────────────────────────────────────

    private final List<String> script    = new ArrayList<>();
    private       int          scriptPtr = 0;

    // ── Call counter ──────────────────────────────────────────────────────

    private int totalCalls = 0;

    // ─────────────────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Construct a stub that always returns {@code response} — identical
     * behaviour to the original {@code StubLLMProvider(String)}.
     */
    public StubLLMProvider(String response) {
        Objects.requireNonNull(response, "response");
        script.add(response);
    }

    /** No-arg constructor for the fluent builder style. */
    public StubLLMProvider() {}

    // ─────────────────────────────────────────────────────────────────────
    // Original static factories (preserved for backward compatibility)
    // ─────────────────────────────────────────────────────────────────────

    /** Single-step stub: always returns a {@code final_answer} decision. */
    public static StubLLMProvider finalAnswer(String text) {
        return new StubLLMProvider(finalAnswerJson(text));
    }

    /** Single-step stub: always returns a {@code tool_call} decision. */
    public static StubLLMProvider toolCall(String tool, String argsJson) {
        return new StubLLMProvider(toolCallJson(tool, argsJson));
    }

    /** Single-step stub: always returns an {@code escalate} decision. */
    public static StubLLMProvider escalate(String reason) {
        return new StubLLMProvider(escalateJson(reason));
    }

    // ─────────────────────────────────────────────────────────────────────
    // JSON template helpers
    // Field names are verified against JsonDecisionParser, ReflexionStrategy,
    // and PlanAndExecuteStrategy source constants.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * {@code final_answer} JSON.
     * Parsed by {@link com.agentframework.reasoning.strategy.JsonDecisionParser}
     * via {@code FIELD_CONTENT} into {@link FinalAnswer#content()}.
     */
    public static String finalAnswerJson(String text) {
        return "{\"type\":\"final_answer\",\"content\":\"" + esc(text)
            + "\",\"reasoning_trace\":\"done\"}";
    }

    /**
     * {@code tool_call} JSON.
     * Parsed by {@link com.agentframework.reasoning.strategy.JsonDecisionParser}
     * via {@code FIELD_TOOL_NAME} and {@code FIELD_ARGUMENTS}.
     */
    public static String toolCallJson(String tool, String argsJson) {
        return "{\"type\":\"tool_call\",\"tool_name\":\"" + tool
            + "\",\"arguments\":" + argsJson
            + ",\"reasoning_trace\":\"calling " + tool + "\"}";
    }

    /**
     * {@code escalate} JSON.
     *
     * <p>{@link com.agentframework.reasoning.strategy.JsonDecisionParser#fromNode}
     * reads the reason from {@code reasoning_trace} and always sets
     * {@code Severity.MEDIUM} regardless of anything in the JSON — the
     * {@code severity} field is therefore absent here.
     */
    public static String escalateJson(String reason) {
        return "{\"type\":\"escalate\",\"reasoning_trace\":\"" + esc(reason) + "\"}";
    }

    /**
     * Embeds the {@code HITL_REQUIRED} raw-string marker that
     * {@link com.agentframework.reasoning.strategy.HitlAwareStrategy}
     * checks before JSON parsing, followed by a valid escalate payload for
     * the parser to consume once the marker has been detected.
     */
    public static String hitlJson() {
        return "HITL_REQUIRED {\"type\":\"escalate\","
            + "\"reasoning_trace\":\"HITL marker \u2014 human approval required\"}";
    }

    /**
     * {@code PLAN} response for
     * {@link com.agentframework.reasoning.strategy.PlanAndExecuteStrategy}.
     *
     * <p>Field names match {@code PlanAndExecuteStrategy} constants exactly:
     * {@code FIELD_MODE="mode"}, {@code FIELD_SUBTASKS="subtasks"},
     * {@code MODE_PLAN="PLAN"}. The strategy encodes the array into
     * {@code AskClarification("PLAN:t1||t2||...")} and pushes subtasks onto
     * the {@code GoalStack}.
     *
     * @param subtasks one or more subtask strings; must not be empty
     * @throws IllegalArgumentException if {@code subtasks} is null or empty
     */
    public static String planJson(String... subtasks) {
        if (subtasks == null || subtasks.length == 0)
            throw new IllegalArgumentException("planJson requires at least one subtask");
        StringBuilder sb = new StringBuilder("{\"mode\":\"PLAN\",\"subtasks\":[");
        for (int i = 0; i < subtasks.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(subtasks[i])).append('"');
        }
        return sb.append("]}").toString();
    }

    /**
     * {@code EXECUTE/tool_call} response for
     * {@link com.agentframework.reasoning.strategy.PlanAndExecuteStrategy}.
     *
     * <p>The strategy delegates to
     * {@link com.agentframework.reasoning.strategy.JsonDecisionParser#fromNode},
     * which reads only {@code type}, {@code tool_name}, and {@code arguments};
     * the {@code mode} field is ignored by the parser but kept for clarity.
     */
    public static String executeToolCallJson(String tool, String argsJson) {
        return "{\"mode\":\"EXECUTE\",\"type\":\"tool_call\","
            + "\"tool_name\":\"" + tool + "\",\"arguments\":" + argsJson
            + ",\"reasoning_trace\":\"executing " + tool + "\"}";
    }

    /**
     * {@code EXECUTE/final_answer} response for
     * {@link com.agentframework.reasoning.strategy.PlanAndExecuteStrategy}.
     */
    public static String executeFinalAnswerJson(String text) {
        return "{\"mode\":\"EXECUTE\",\"type\":\"final_answer\","
            + "\"content\":\"" + esc(text) + "\",\"reasoning_trace\":\"done\"}";
    }

    /**
     * {@code ReflexionStrategy} JSON with {@code critique.next=RETRY}.
     *
     * <p>{@link com.agentframework.reasoning.strategy.ReflexionStrategy#parse}
     * reads {@code critique.next}, matches {@code NextAction.RETRY}, and
     * returns {@code new AskClarification(RETRY_MARKER)}.
     * The {@code action} block is present but ignored on this path.
     */
    public static String reflexionRetryJson(String critiqueText,
                                            String toolName, String argsJson) {
        return "{\"critique\":{\"assessment\":\"" + esc(critiqueText)
            + "\",\"next\":\"RETRY\"},"
            + "\"action\":{\"type\":\"tool_call\",\"tool_name\":\"" + toolName
            + "\",\"arguments\":" + argsJson
            + ",\"reasoning_trace\":\"will retry\"}}";
    }

    /**
     * {@code ReflexionStrategy} JSON with {@code critique.next=PROCEED}.
     *
     * <p>{@link com.agentframework.reasoning.strategy.ReflexionStrategy#parse}
     * delegates to
     * {@link com.agentframework.reasoning.strategy.JsonDecisionParser#fromNode}
     * on the {@code action} node, producing a {@link ToolCall}.
     */
    public static String reflexionProceedJson(String toolName, String argsJson) {
        return "{\"critique\":{\"assessment\":\"looks good\",\"next\":\"PROCEED\"},"
            + "\"action\":{\"type\":\"tool_call\",\"tool_name\":\"" + toolName
            + "\",\"arguments\":" + argsJson
            + ",\"reasoning_trace\":\"proceeding\"}}";
    }

    /**
     * {@code ReflexionStrategy} JSON with {@code critique.next=ANSWER}.
     *
     * <p>Uses {@code ANSWER} (not {@code PROCEED}) so that
     * {@link com.agentframework.reasoning.strategy.ReflexionStrategy#parse}
     * enters the {@code case ANSWER} branch and calls
     * {@code parseAnswerFromAction()}, exercising the guard that validates
     * {@code action.content}. Using {@code PROCEED} would bypass that branch
     * entirely and leave it untested.
     */
    public static String reflexionFinalAnswerJson(String text) {
        return "{\"critique\":{\"assessment\":\"complete\",\"next\":\"ANSWER\"},"
            + "\"action\":{\"type\":\"final_answer\","
            + "\"content\":\"" + esc(text) + "\",\"reasoning_trace\":\"done\"}}";
    }

    /**
     * Intentionally malformed JSON — drives the
     * {@link com.agentframework.reasoning.strategy.JsonDecisionParser}
     * never-throws fallback path, which returns a {@code HIGH}-severity
     * {@link Escalate}.
     *
     * <p>Use with {@link #onCall} to inject a parse failure at a specific
     * cycle and verify the runner's revision-budget logic
     * ({@code isRevisionBudgetExceeded(3)}).
     */
    public static String malformedJson() {
        return "{ this is not valid json ]]";
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fluent builder methods
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Append {@code jsonResponse} to the sequential script.
     * Entries are consumed in order; the last entry repeats on exhaustion.
     */
    public StubLLMProvider then(String jsonResponse) {
        Objects.requireNonNull(jsonResponse, "jsonResponse");
        script.add(jsonResponse);
        return this;
    }

    /**
     * Override the sequential script for call index {@code index} (0-based).
     * Evaluated before prompt-content routes and the sequential script.
     */
    public StubLLMProvider onCall(int index, String jsonResponse) {
        Objects.requireNonNull(jsonResponse, "jsonResponse");
        indexRoutes.add(new IndexRoute(index, jsonResponse));
        return this;
    }

    /**
     * Return {@code jsonResponse} when the last USER message in the prompt
     * contains {@code keyword} (case-sensitive substring match).
     * Evaluated after index routes, before the sequential script.
     *
     * <p>Covers the case where {@link PromptBuilder} injects a tool result
     * or staleness hint that the stub should react to.
     */
    public StubLLMProvider whenPromptContains(String keyword, String jsonResponse) {
        Objects.requireNonNull(keyword,      "keyword");
        Objects.requireNonNull(jsonResponse, "jsonResponse");
        contentRoutes.add(new ContentRoute(msg -> msg.contains(keyword), jsonResponse));
        return this;
    }

    /**
     * Return {@code jsonResponse} when the custom {@link Predicate} on the
     * last USER message returns {@code true}.
     */
    public StubLLMProvider whenPrompt(Predicate<String> predicate,
                                      String jsonResponse) {
        Objects.requireNonNull(predicate,    "predicate");
        Objects.requireNonNull(jsonResponse, "jsonResponse");
        contentRoutes.add(new ContentRoute(predicate, jsonResponse));
        return this;
    }

    // ─────────────────────────────────────────────────────────────────────
    // LLMProvider contract
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Dispatch order:
     * <ol>
     *   <li>Call-index routes (insertion order)</li>
     *   <li>Prompt-content routes (insertion order)</li>
     *   <li>Sequential script (advances pointer; clamps at last entry)</li>
     *   <li>{@link #FALLBACK_JSON}</li>
     * </ol>
     */
    @Override
    public String generate(Prompt prompt) {
        int idx = totalCalls++;

        for (Route r : indexRoutes)
            if (r.matches(idx, prompt)) return r.response();

        for (Route r : contentRoutes)
            if (r.matches(idx, prompt)) return r.response();

        if (!script.isEmpty()) {
            String response = script.get(Math.min(scriptPtr, script.size() - 1));
            if (scriptPtr < script.size() - 1) scriptPtr++;
            return response;
        }

        return FALLBACK_JSON;
    }

    @Override
    public String name() { return "stub"; }

    /**
     * Total number of times {@link #generate} was called.
     *
     * <p>Because {@link StateMachineRunner} calls {@code decide()} exactly
     * once per live cycle, {@code assertEquals(n, llm.callCount())} asserts
     * that the run completed in exactly {@code n} cycles.
     */
    public int callCount() { return totalCalls; }

    // ─────────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Extracts the content of the last USER-role {@link Message} from the
     * prompt. Returns an empty string when no USER message exists.
     *
     * <p>Package-private so inner records can call
     * {@code StubLLMProvider.lastUserMessage(prompt)} from a single
     * implementation — no duplication.
     */
    static String lastUserMessage(Prompt p) {
        return p.messages().stream()
            .filter(m -> m.role() == Message.Role.USER)
            .reduce((a, b) -> b)
            .map(Message::content)
            .orElse("");
    }

    /** Minimal JSON-safe escaping: backslash then double-quote. */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "'");
    }

    // ── Route types ───────────────────────────────────────────────────────

    private sealed interface Route permits IndexRoute, ContentRoute {
        boolean matches(int callIndex, Prompt prompt);
        String  response();
    }

    private record IndexRoute(int index, String response) implements Route {
        public boolean matches(int callIndex, Prompt prompt) {
            return callIndex == index;
        }
    }

    /**
     * Delegates to the outer-class {@link StubLLMProvider#lastUserMessage}
     * — single implementation, no duplication between outer class and record.
     */
    private record ContentRoute(Predicate<String> predicate,
                                 String response) implements Route {
        public boolean matches(int callIndex, Prompt prompt) {
            return predicate.test(StubLLMProvider.lastUserMessage(prompt));
        }
    }
}
