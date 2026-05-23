package com.agentframework.security;

import com.agentframework.foundation.TaintLabel;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Classifies arbitrary strings (tool output, RAG passages, web content) into
 * {@link TaintLabel} tiers.
 *
 * <p><b>Classification rules</b>
 * <ol>
 *   <li>{@code null} / blank input → {@link TaintLabel#CLEAN} (nothing to taint).</li>
 *   <li>Non-empty string that matches one or more hostile patterns →
 *       {@link TaintLabel#HOSTILE}; the call-site blocks tool dispatch.</li>
 *   <li>All other non-empty external strings → {@link TaintLabel#EXTERNAL}; the
 *       content enters working memory tagged for provenance tracking but does not
 *       block execution by itself.</li>
 * </ol>
 *
 * <p><b>Pattern coverage</b><br>
 * Detects instruction-override injections, role/system-prompt spoofing, jailbreak
 * tokens, and LLM delimiter injections (ChatML, Anthropic, custom variants).
 * Patterns are compiled once at class-load time; all matching is case-insensitive
 * and DOTALL so multi-line payloads are handled correctly.
 *
 * <p>Thread-safe: immutable after construction.
 */
public final class TaintClassifier {

    private static final List<Pattern> HOSTILE_PATTERNS = List.of(
        // ── Instruction-override attacks ────────────────────────────────────
        Pattern.compile(
            "(?is)ignore\\s+(all\\s+)?(previous|prior|above|earlier)\\s+(instructions?|prompts?|context|directives?)"
        ),
        Pattern.compile(
            "(?is)disregard\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?)"
        ),
        Pattern.compile(
            "(?is)forget\\s+(everything|all|your\\s+instructions?)"
        ),
        Pattern.compile(
            "(?is)do\\s+not\\s+follow\\s+(the\\s+)?(previous|prior|above|earlier)\\s+(instructions?|prompts?)"
        ),
        // ── Role / persona override ──────────────────────────────────────────
        Pattern.compile(
            "(?is)(you\\s+are\\s+now|act\\s+as|pretend\\s+(you\\s+are|to\\s+be))\\s+.{0,100}(assistant|ai|system|gpt|claude|llm|bot|model)"
        ),
        Pattern.compile(
            "(?is)switch\\s+(to|into)\\s+(developer|jailbreak|unrestricted|admin|root|sudo)\\s+mode"
        ),
        // ── System prompt / delimiter injection ─────────────────────────────
        Pattern.compile("(?is)\\[\\s*system\\s*\\]"),
        Pattern.compile("(?is)<\\s*system\\s*>"),
        Pattern.compile("(?is)###\\s*(instruction|system|user|assistant)\\s*:"),
        Pattern.compile("(?is)<\\|?(im_start|im_end|system|user|assistant)\\|?>"),
        Pattern.compile("(?is)\\x00.*system"),          // null-byte prefix
        Pattern.compile("(?is)\\u200b.*system"),        // zero-width space prefix
        // ── Prompt-leakage probes ────────────────────────────────────────────
        Pattern.compile(
            "(?is)(print|show|reveal|output|repeat|return|tell me)\\s+(your\\s+)?(system\\s+prompt|initial\\s+instructions?|full\\s+prompt|base\\s+prompt)"
        ),
        // ── Jailbreak tokens ────────────────────────────────────────────────
        Pattern.compile("(?is)\\bDAN\\b"),
        Pattern.compile("(?is)jailbreak"),
        Pattern.compile("(?is)grandma\\s+(trick|exploit|bypass)"),
        // ── Override via payload framing ────────────────────────────────────
        Pattern.compile("(?is)\\bNEW\\s+INSTRUCTIONS?\\b"),
        Pattern.compile("(?is)\\bOVERRIDE\\s+INSTRUCTIONS?\\b"),
        Pattern.compile("(?is)\\bSYSTEM\\s+MESSAGE\\s*:"),
        Pattern.compile("(?is)\\bUSER\\s+MESSAGE\\s*:")
    );

    /**
     * Classifies a raw string returned by a tool or retrieved from external storage.
     *
     * @param text the string to classify; may be {@code null}
     * @return the appropriate {@link TaintLabel} (never {@code null})
     */
    public TaintLabel classify(String text) {
        if (text == null || text.isBlank()) return TaintLabel.CLEAN;
        for (Pattern p : HOSTILE_PATTERNS) {
            if (p.matcher(text).find()) return TaintLabel.HOSTILE;
        }
        return TaintLabel.EXTERNAL;
    }

    /**
     * Convenience overload: converts {@code data} via {@link Object#toString()} and classifies.
     * {@code null} input → {@link TaintLabel#CLEAN}.
     */
    public TaintLabel classifyObject(Object data) {
        if (data == null) return TaintLabel.CLEAN;
        return classify(data.toString());
    }
}
