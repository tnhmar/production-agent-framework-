package com.agentframework.tests;

import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.perception.*;
import com.agentframework.perception.stages.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class PerceptionTest {

    // ───────────────────────────────────────────────────────────────────────
    // DefaultTypeIdentifier
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void typeIdentifier_textString() {
        assertEquals(InputType.TEXT, new DefaultTypeIdentifier().identify("hello world"));
    }

    @Test
    public void typeIdentifier_jsonString() {
        assertEquals(InputType.JSON, new DefaultTypeIdentifier().identify("{\"key\":\"value\"}"));
    }

    @Test
    public void typeIdentifier_jsonWithLeadingSpace() {
        assertEquals(InputType.JSON, new DefaultTypeIdentifier().identify("  { \"a\": 1 }"));
    }

    @Test
    public void typeIdentifier_nonStringObject() {
        assertEquals(InputType.UNKNOWN, new DefaultTypeIdentifier().identify(42));
    }

    // ───────────────────────────────────────────────────────────────────────
    // DefaultFormatParser
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void formatParser_nullRaw_producesEmpty() {
        ParsedContent c = new DefaultFormatParser().parse(null, InputType.TEXT);
        assertEquals("", c.textContent());
    }

    @Test
    public void formatParser_nonNullRaw_usesToString() {
        ParsedContent c = new DefaultFormatParser().parse("hello", InputType.TEXT);
        assertEquals("hello", c.textContent());
    }

    // ───────────────────────────────────────────────────────────────────────
    // DefaultNormalizer
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void normalizer_nullTextBecomesEmpty() {
        NormalizedContent nc = new DefaultNormalizer()
                .normalize(new ParsedContent(null, Map.of(), java.util.List.of()));
        assertEquals("", nc.text());
    }

    @Test
    public void normalizer_collapsesWhitespace() {
        NormalizedContent nc = new DefaultNormalizer()
                .normalize(new ParsedContent("  hello   world  ", Map.of(), java.util.List.of()));
        assertEquals("hello world", nc.text());
    }

    @Test
    public void normalizer_plainText_unchanged() {
        NormalizedContent nc = new DefaultNormalizer()
                .normalize(new ParsedContent("clean", Map.of(), java.util.List.of()));
        assertEquals("clean", nc.text());
    }

    // ───────────────────────────────────────────────────────────────────────
    // DefaultMetadataInjector
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void metadataInjector_userOriginGivesHighTrust() {
        AnnotatedContent ac = new DefaultMetadataInjector()
                .injectMetadata(new NormalizedContent("text", Map.of()), InputOrigin.USER);
        assertEquals(TrustTier.HIGH,   ac.trustTier());
        assertEquals(InputOrigin.USER, ac.origin());
    }

    @Test
    public void metadataInjector_systemOriginGivesHighTrust() {
        assertEquals(TrustTier.HIGH, new DefaultMetadataInjector()
                .injectMetadata(new NormalizedContent("x", Map.of()), InputOrigin.SYSTEM).trustTier());
    }

    @Test
    public void metadataInjector_toolOriginGivesMediumTrust() {
        assertEquals(TrustTier.MEDIUM, new DefaultMetadataInjector()
                .injectMetadata(new NormalizedContent("x", Map.of()), InputOrigin.TOOL).trustTier());
    }

    @Test
    public void metadataInjector_memoryOriginGivesMediumTrust() {
        assertEquals(TrustTier.MEDIUM, new DefaultMetadataInjector()
                .injectMetadata(new NormalizedContent("x", Map.of()), InputOrigin.MEMORY).trustTier());
    }

    @Test
    public void metadataInjector_externalOriginGivesLowTrust() {
        assertEquals(TrustTier.LOW, new DefaultMetadataInjector()
                .injectMetadata(new NormalizedContent("x", Map.of()), InputOrigin.EXTERNAL).trustTier());
    }

    // ───────────────────────────────────────────────────────────────────────
    // TokenBudgetManager
    // ───────────────────────────────────────────────────────────────────────

    private AnnotatedContent makeAC(String text) {
        return new AnnotatedContent(
                new NormalizedContent(text, Map.of()),
                InputOrigin.USER, TrustTier.HIGH, Instant.now(), "test");
    }

    @Test
    public void tokenBudget_zeroBudgetPassthrough() {
        TokenBudgetManager mgr = new TokenBudgetManager(TokenEstimator.heuristic());
        AnnotatedContent ac = makeAC("some text");
        assertSame(ac, mgr.enforceTokenBudget(ac, 0));
    }

    @Test
    public void tokenBudget_withinBudgetPassthrough() {
        TokenBudgetManager mgr = new TokenBudgetManager(TokenEstimator.heuristic());
        AnnotatedContent ac = makeAC("hi"); // 2 chars = 0 tokens (2/4=0) < 10
        assertSame(ac, mgr.enforceTokenBudget(ac, 10));
    }

    @Test
    public void tokenBudget_overBudgetTruncates() {
        TokenBudgetManager mgr = new TokenBudgetManager(TokenEstimator.heuristic());
        String longText = "ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMN"; // 40 chars = 10 tokens
        AnnotatedContent result = mgr.enforceTokenBudget(makeAC(longText), 2);
        assertTrue(result.content().text().length() <= 8);
    }

    // ───────────────────────────────────────────────────────────────────────
    // InputNormalizationPipeline end-to-end
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void pipeline_defaults_processText() {
        AnnotatedContent ac = InputNormalizationPipeline.defaults()
                .process("  Hello   World  ", InputOrigin.USER, 4096);
        assertEquals("Hello World", ac.content().text());
        assertEquals(TrustTier.HIGH, ac.trustTier());
    }

    @Test
    public void pipeline_defaults_processJson() {
        AnnotatedContent ac = InputNormalizationPipeline.defaults()
                .process("{\"k\":\"v\"}", InputOrigin.TOOL, 4096);
        assertEquals(TrustTier.MEDIUM, ac.trustTier());
    }

    @Test
    public void pipeline_defaults_budgetEnforced() {
        String longText = "ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMN";
        AnnotatedContent ac = InputNormalizationPipeline.defaults()
                .process(longText, InputOrigin.USER, 2);
        assertTrue(ac.content().text().length() <= 8);
    }

    // ───────────────────────────────────────────────────────────────────────
    // SimplePerception
    // ───────────────────────────────────────────────────────────────────────

    /** Helper: builds a WorkingMemoryEntry with sensible defaults. */
    private static WorkingMemoryEntry wme(Origin origin, String content) {
        return new WorkingMemoryEntry(
                UUID.randomUUID().toString(), content,
                WorkingMemoryTier.ACTIVE, origin,
                1.0, Instant.now(), TaintLabel.CLEAN);
    }

    private DefaultExecutionContext makeCtx(String instruction) {
        return new DefaultExecutionContext(
                Task.builder().instruction(instruction).build(), "t1", "u1");
    }

    @Test
    public void simplePerception_userEntry_producesObservation() {
        DefaultExecutionContext ctx = makeCtx("do X");
        ctx.workingMemory().add(wme(Origin.USER, "user message"));

        Observations obs = new SimplePerception().perceive(ctx);

        assertEquals(1, obs.items().size());
        assertEquals("user message", obs.items().get(0).content());
        assertEquals(TrustTier.HIGH, obs.items().get(0).trustTier());
    }

    @Test
    public void simplePerception_toolEntry_producesObservation() {
        DefaultExecutionContext ctx = makeCtx("do Y");
        ctx.workingMemory().add(wme(Origin.TOOL, "tool result"));

        Observations obs = new SimplePerception().perceive(ctx);

        assertEquals(1, obs.items().size());
        assertEquals(Origin.TOOL, obs.items().get(0).origin());
    }

    @Test
    public void simplePerception_emptyWM_fallsBackToGoal() {
        DefaultExecutionContext ctx = makeCtx("find the answer");
        ctx.goalStack().push(new Goal("root", null, GoalStatus.ACTIVE,
                "find the answer", java.util.List.of(), Budget.unlimited()));

        Observations obs = new SimplePerception().perceive(ctx);

        assertEquals(1, obs.items().size());
        assertEquals("find the answer", obs.items().get(0).content());
        assertEquals(Origin.SYSTEM, obs.items().get(0).origin());
    }

    @Test
    public void simplePerception_emptyWMAndNoGoal_returnsEmpty() {
        Observations obs = new SimplePerception().perceive(makeCtx("task"));
        assertTrue(obs.items().isEmpty());
    }

    @Test
    public void simplePerception_marksEntriesProcessed() {
        DefaultExecutionContext ctx = makeCtx("do Z");
        ctx.workingMemory().add(wme(Origin.USER, "msg"));

        SimplePerception p = new SimplePerception();
        p.perceive(ctx);
        assertTrue(new SimplePerception().perceive(ctx).items().isEmpty(),
                "already-processed entries must not be re-emitted");
    }
}
