package com.agentframework.tests;

import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.perception.*;
import com.agentframework.perception.stages.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;

public class PerceptionTest {

    // ───────────────────────────────────────────────────────────────────────
    // DefaultTypeIdentifier
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void typeIdentifier_textString() {
        DefaultTypeIdentifier id = new DefaultTypeIdentifier();
        assertEquals(InputType.TEXT, id.identify("hello world"));
    }

    @Test
    public void typeIdentifier_jsonString() {
        DefaultTypeIdentifier id = new DefaultTypeIdentifier();
        assertEquals(InputType.JSON, id.identify("{\"key\":\"value\"}"));
    }

    @Test
    public void typeIdentifier_jsonWithLeadingSpace() {
        DefaultTypeIdentifier id = new DefaultTypeIdentifier();
        assertEquals(InputType.JSON, id.identify("  { \"a\": 1 }"));
    }

    @Test
    public void typeIdentifier_nonStringObject() {
        DefaultTypeIdentifier id = new DefaultTypeIdentifier();
        assertEquals(InputType.UNKNOWN, id.identify(42));
    }

    // ───────────────────────────────────────────────────────────────────────
    // DefaultFormatParser
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void formatParser_nullRaw_producesEmpty() {
        DefaultFormatParser p = new DefaultFormatParser();
        ParsedContent c = p.parse(null, InputType.TEXT);
        assertEquals("", c.textContent());
    }

    @Test
    public void formatParser_nonNullRaw_usesToString() {
        DefaultFormatParser p = new DefaultFormatParser();
        ParsedContent c = p.parse("hello", InputType.TEXT);
        assertEquals("hello", c.textContent());
    }

    // ───────────────────────────────────────────────────────────────────────
    // DefaultNormalizer
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void normalizer_nullTextBecomesEmpty() {
        DefaultNormalizer n = new DefaultNormalizer();
        ParsedContent pc = new ParsedContent(null, Map.of(), java.util.List.of());
        NormalizedContent nc = n.normalize(pc);
        assertEquals("", nc.text());
    }

    @Test
    public void normalizer_collapsesWhitespace() {
        DefaultNormalizer n = new DefaultNormalizer();
        ParsedContent pc = new ParsedContent("  hello   world  ", Map.of(), java.util.List.of());
        NormalizedContent nc = n.normalize(pc);
        assertEquals("hello world", nc.text());
    }

    @Test
    public void normalizer_plainText_unchanged() {
        DefaultNormalizer n = new DefaultNormalizer();
        ParsedContent pc = new ParsedContent("clean", Map.of(), java.util.List.of());
        NormalizedContent nc = n.normalize(pc);
        assertEquals("clean", nc.text());
    }

    // ───────────────────────────────────────────────────────────────────────
    // DefaultMetadataInjector
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void metadataInjector_userOriginGivesHighTrust() {
        DefaultMetadataInjector inj = new DefaultMetadataInjector();
        NormalizedContent nc = new NormalizedContent("text", Map.of());
        AnnotatedContent ac = inj.injectMetadata(nc, InputOrigin.USER);
        assertEquals(TrustTier.HIGH,   ac.trustTier());
        assertEquals(InputOrigin.USER, ac.origin());
    }

    @Test
    public void metadataInjector_systemOriginGivesHighTrust() {
        DefaultMetadataInjector inj = new DefaultMetadataInjector();
        NormalizedContent nc = new NormalizedContent("text", Map.of());
        assertEquals(TrustTier.HIGH, inj.injectMetadata(nc, InputOrigin.SYSTEM).trustTier());
    }

    @Test
    public void metadataInjector_toolOriginGivesMediumTrust() {
        DefaultMetadataInjector inj = new DefaultMetadataInjector();
        NormalizedContent nc = new NormalizedContent("text", Map.of());
        assertEquals(TrustTier.MEDIUM, inj.injectMetadata(nc, InputOrigin.TOOL).trustTier());
    }

    @Test
    public void metadataInjector_memoryOriginGivesMediumTrust() {
        DefaultMetadataInjector inj = new DefaultMetadataInjector();
        NormalizedContent nc = new NormalizedContent("text", Map.of());
        assertEquals(TrustTier.MEDIUM, inj.injectMetadata(nc, InputOrigin.MEMORY).trustTier());
    }

    @Test
    public void metadataInjector_externalOriginGivesLowTrust() {
        DefaultMetadataInjector inj = new DefaultMetadataInjector();
        NormalizedContent nc = new NormalizedContent("text", Map.of());
        assertEquals(TrustTier.LOW, inj.injectMetadata(nc, InputOrigin.EXTERNAL).trustTier());
    }

    // ───────────────────────────────────────────────────────────────────────
    // TokenBudgetManager
    // ───────────────────────────────────────────────────────────────────────

    private AnnotatedContent makeAC(String text) {
        NormalizedContent nc = new NormalizedContent(text, Map.of());
        return new AnnotatedContent(nc, InputOrigin.USER, TrustTier.HIGH, Instant.now(), "test");
    }

    @Test
    public void tokenBudget_zeroBudgetPassthrough() {
        TokenBudgetManager mgr = new TokenBudgetManager(TokenEstimator.heuristic());
        AnnotatedContent ac = makeAC("some text");
        assertSame(ac, mgr.enforceTokenBudget(ac, 0), "budget=0 must return identity");
    }

    @Test
    public void tokenBudget_withinBudgetPassthrough() {
        TokenBudgetManager mgr = new TokenBudgetManager(TokenEstimator.heuristic());
        AnnotatedContent ac = makeAC("hi");
        assertSame(ac, mgr.enforceTokenBudget(ac, 10), "within budget -> identity");
    }

    @Test
    public void tokenBudget_overBudgetTruncates() {
        TokenBudgetManager mgr = new TokenBudgetManager(TokenEstimator.heuristic());
        String longText = "ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMN"; // 40 chars = 10 tokens
        AnnotatedContent result = mgr.enforceTokenBudget(makeAC(longText), 2);
        assertTrue(result.content().text().length() <= 8, "text truncated to budget*4 chars");
    }

    // ───────────────────────────────────────────────────────────────────────
    // InputNormalizationPipeline end-to-end
    // ───────────────────────────────────────────────────────────────────────

    @Test
    public void pipeline_defaults_processText() {
        InputNormalizationPipeline pipeline = InputNormalizationPipeline.defaults();
        AnnotatedContent ac = pipeline.process("  Hello   World  ", InputOrigin.USER, 4096);
        assertEquals("Hello World", ac.content().text(), "whitespace normalised");
        assertEquals(TrustTier.HIGH, ac.trustTier(), "USER -> HIGH trust");
    }

    @Test
    public void pipeline_defaults_processJson() {
        InputNormalizationPipeline pipeline = InputNormalizationPipeline.defaults();
        AnnotatedContent ac = pipeline.process("{\"k\":\"v\"}", InputOrigin.TOOL, 4096);
        assertEquals(TrustTier.MEDIUM, ac.trustTier(), "TOOL -> MEDIUM trust");
    }

    @Test
    public void pipeline_defaults_budgetEnforced() {
        InputNormalizationPipeline pipeline = InputNormalizationPipeline.defaults();
        String longText = "ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMN";
        AnnotatedContent ac = pipeline.process(longText, InputOrigin.USER, 2);
        assertTrue(ac.content().text().length() <= 8, "budget truncation applied end-to-end");
    }

    // ───────────────────────────────────────────────────────────────────────
    // SimplePerception
    // ───────────────────────────────────────────────────────────────────────

    private DefaultExecutionContext makeCtx(String instruction) {
        Task t = Task.builder().instruction(instruction).build();
        return new DefaultExecutionContext(t, "t1", "u1");
    }

    @Test
    public void simplePerception_userEntry_producesObservation() {
        DefaultExecutionContext ctx = makeCtx("do X");
        ctx.workingMemory().add(Origin.USER, "user message", TaintLabel.CLEAN);

        SimplePerception p = new SimplePerception();
        Observations obs = p.perceive(ctx);

        assertEquals(1, obs.items().size(), "one observation from one WM entry");
        assertEquals("user message", obs.items().get(0).content());
        assertEquals(TrustTier.HIGH, obs.items().get(0).trustTier());
    }

    @Test
    public void simplePerception_toolEntry_producesObservation() {
        DefaultExecutionContext ctx = makeCtx("do Y");
        ctx.workingMemory().add(Origin.TOOL, "tool result", TaintLabel.CLEAN);

        SimplePerception p = new SimplePerception();
        Observations obs = p.perceive(ctx);

        assertEquals(1, obs.items().size());
        assertEquals(Origin.TOOL, obs.items().get(0).origin());
    }

    @Test
    public void simplePerception_emptyWM_fallsBackToGoal() {
        DefaultExecutionContext ctx = makeCtx("find the answer");
        Goal root = new Goal("root", null, GoalStatus.ACTIVE,
                "find the answer", java.util.List.of(), Budget.unlimited());
        ctx.goalStack().push(root);

        SimplePerception p = new SimplePerception();
        Observations obs = p.perceive(ctx);

        assertEquals(1, obs.items().size(), "goal description becomes seed observation");
        assertEquals("find the answer", obs.items().get(0).content());
        assertEquals(Origin.SYSTEM, obs.items().get(0).origin());
    }

    @Test
    public void simplePerception_emptyWMAndNoGoal_returnsEmpty() {
        DefaultExecutionContext ctx = makeCtx("task");
        SimplePerception p = new SimplePerception();
        Observations obs = p.perceive(ctx);
        assertTrue(obs.items().isEmpty(), "empty WM + no goal -> empty observations");
    }

    @Test
    public void simplePerception_marksEntriesProcessed() {
        DefaultExecutionContext ctx = makeCtx("do Z");
        ctx.workingMemory().add(Origin.USER, "msg", TaintLabel.CLEAN);

        SimplePerception p = new SimplePerception();
        p.perceive(ctx);

        Observations second = p.perceive(ctx);
        assertTrue(second.items().isEmpty(), "already-processed entries not re-emitted");
    }
}
