package com.agentframework.perception;

import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.memory.*;
import com.agentframework.perception.stages.*;
import com.agentframework.rag.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Full-pipeline perception implementation.
 *
 * <p><b>M3 fix</b>: taint labels from retrieved {@link MemoryRecord}s are
 * preserved in the resulting {@link Observation} — not silently defaulted
 * to CLEAN.
 *
 * <p><b>IC3 fix</b>: ALL observations (including memory and RAG retrievals)
 * pass through {@link GroundingService#ground(Observation, ExecutionContext)}
 * before being returned.
 *
 * <p><b>topK</b>: retrieval depth is derived from the token budget rather
 * than hardcoded, capped at {@value #MAX_RETRIEVAL_K} per source.  Estimated
 * at {@value #AVG_PASSAGE_TOKENS} tokens per passage.
 *
 * <p><b>buildQuery</b>: the retrieval query is limited to 512 characters and
 * is built from goal description + entity mentions from recent TOOL
 * observations only — not a raw content concatenation that could balloon the
 * query and degrade retrieval quality.
 */
public class DefaultPerception implements Perception {

    /** Estimated token cost per retrieved passage — used to derive dynamic topK. */
    private static final int AVG_PASSAGE_TOKENS = 150;
    /** Hard cap on number of passages retrieved per source to guard context window. */
    private static final int MAX_RETRIEVAL_K    = 20;
    /** Maximum character length of the retrieval query string. */
    private static final int MAX_QUERY_CHARS    = 512;

    private final InputNormalizationPipeline pipeline;
    private final Memory           memory;
    private final RagService       ragService;
    private final GroundingService grounding;
    private final RelevanceFilter  relevanceFilter;
    private final int              tokenBudget;

    public DefaultPerception(InputNormalizationPipeline pipeline, Memory memory,
            RagService ragService, GroundingService grounding,
            RelevanceFilter relevanceFilter, int tokenBudget) {
        this.pipeline        = pipeline;
        this.memory          = memory;
        this.ragService      = ragService;
        this.grounding       = grounding;
        this.relevanceFilter = relevanceFilter;
        this.tokenBudget     = tokenBudget;
    }

    public Observations perceive(ExecutionContext ctx) {
        List<Observation> obs = new ArrayList<>();

        // ── 1. Normalize unread working-memory entries ───────────────────
        // Only USER and TOOL origins are processed; SYSTEM and MEMORY entries
        // are excluded by the filter — the switch arms for those origins
        // are therefore removed to avoid dead code.
        List<WorkingMemoryEntry> unread = ctx.workingMemory().getUnprocessed().stream()
            .filter(e -> e.origin() == Origin.USER || e.origin() == Origin.TOOL)
            .toList();

        for (WorkingMemoryEntry entry : unread) {
            InputOrigin origin = switch (entry.origin()) {
                case USER -> InputOrigin.USER;
                case TOOL -> InputOrigin.TOOL;
                // SYSTEM and MEMORY are filtered out above; no dead branches here.
                default   -> InputOrigin.EXTERNAL;
            };
            AnnotatedContent ann = pipeline.process(entry.content(), origin, tokenBudget);
            Observation o = new Observation(
                ann.content().text(), entry.origin(),
                ann.trustTier(), entry.taintLabel(),   // taint from WM entry preserved
                ann.timestamp(), ann.sourceReference());
            o = grounding.ground(o, ctx);              // IC3: ground all obs
            obs.add(o);
            ctx.workingMemory().markProcessed(entry.id());
        }

        // ── 2. Long-term memory retrieval ────────────────────────────────
        String query = buildQuery(ctx, obs);
        if (query != null && !query.isBlank()) {
            int memTopK = dynamicTopK(tokenBudget);
            RequestContext reqCtx = ctx.requestContext();
            memory.retrieve(MemoryQuery.text(query), memTopK, reqCtx).forEach(r -> {
                // M3 fix: carry the MemoryRecord's taint into the Observation.
                TaintLabel taint = r.taintLabel() != null ? r.taintLabel() : TaintLabel.CLEAN;
                Observation o = new Observation(
                    r.content().text(), Origin.MEMORY,
                    TrustTier.MEDIUM, taint,
                    Instant.now(), "memory:" + r.id());
                obs.add(grounding.ground(o, ctx)); // IC3: ground memory obs too
            });
        }

        // ── 3. RAG retrieval ─────────────────────────────────────────────
        if (query != null && !query.isBlank()) {
            int ragTopK = dynamicTopK(tokenBudget);
            ragService.retrieve(new RagQuery(query, ragTopK, Map.of())).forEach(p -> {
                // RAG passages come from external sources — label as EXTERNAL taint.
                Observation o = new Observation(
                    "[" + p.sourceId() + "] " + p.text(),
                    Origin.RETRIEVAL, TrustTier.MEDIUM, TaintLabel.EXTERNAL,
                    Instant.now(), p.sourceId());
                obs.add(grounding.ground(o, ctx)); // IC3: ground RAG obs too
            });
        }

        // ── 4. Relevance filter ───────────────────────────────────────────
        Goal goal = ctx.goalStack().current().orElse(null);
        List<Observation> filtered = relevanceFilter.filter(obs, goal);

        return Observations.of(filtered);
    }

    /**
     * Derive topK from the available token budget: reserve half the budget
     * for retrieval, spread across estimated passage size, capped at
     * {@value #MAX_RETRIEVAL_K}.
     */
    private int dynamicTopK(int budget) {
        int derived = Math.max(1, (budget / 2) / AVG_PASSAGE_TOKENS);
        return Math.min(derived, MAX_RETRIEVAL_K);
    }

    /**
     * Build a retrieval query from the current goal and entity mentions
     * extracted from recent TOOL observations.
     *
     * <p>Uses goal description as the primary signal, supplemented by
     * entity-like tokens (words >=4 chars, capitalised or domain-specific)
     * from TOOL observations only to avoid noise from raw user prose.  The
     * resulting string is capped at {@value #MAX_QUERY_CHARS} characters.
     */
    private String buildQuery(ExecutionContext ctx, List<Observation> obs) {
        return ctx.goalStack().current().map(g -> {
            StringBuilder sb = new StringBuilder(g.description());
            // Append entity mentions from TOOL observations only.
            obs.stream()
                .filter(o -> o.origin() == Origin.TOOL)
                .flatMap(o -> Arrays.stream(o.content().split("\\s+")))
                .filter(tok -> tok.length() >= 4)
                .distinct()
                .limit(20)
                .forEach(tok -> { if (sb.length() < MAX_QUERY_CHARS) sb.append(' ').append(tok); });
            String q = sb.toString().strip();
            return q.length() > MAX_QUERY_CHARS ? q.substring(0, MAX_QUERY_CHARS) : q;
        }).orElse(null);
    }
}
