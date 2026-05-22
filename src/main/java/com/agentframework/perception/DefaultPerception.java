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
 * <p>M3 fix: taint labels from retrieved {@link MemoryRecord}s are preserved in
 * the resulting {@link Observation} — not silently defaulted to CLEAN.
 *
 * <p>IC3 fix: ALL observations (including memory and RAG retrievals) pass through
 * {@link GroundingService#ground(Observation, ExecutionContext)} before being returned.
 */
public class DefaultPerception implements Perception {
    private final InputNormalizationPipeline pipeline;
    private final Memory           memory;
    private final RagService       ragService;
    private final GroundingService grounding;
    private final RelevanceFilter  relevanceFilter;
    private final int              tokenBudget;

    public DefaultPerception(InputNormalizationPipeline pipeline, Memory memory,
            RagService ragService, GroundingService grounding,
            RelevanceFilter relevanceFilter, int tokenBudget) {
        this.pipeline = pipeline; this.memory = memory; this.ragService = ragService;
        this.grounding = grounding; this.relevanceFilter = relevanceFilter;
        this.tokenBudget = tokenBudget;
    }

    public Observations perceive(ExecutionContext ctx) {
        List<Observation> obs = new ArrayList<>();

        // ── 1. Normalize unread working-memory entries ───────────────────
        List<WorkingMemoryEntry> unread = ctx.workingMemory().getUnprocessed().stream()
            .filter(e -> e.origin() == Origin.USER || e.origin() == Origin.TOOL)
            .toList();
        for (WorkingMemoryEntry entry : unread) {
            InputOrigin origin = switch (entry.origin()) {
                case USER      -> InputOrigin.USER;
                case TOOL      -> InputOrigin.TOOL;
                case SYSTEM    -> InputOrigin.SYSTEM;
                case MEMORY    -> InputOrigin.MEMORY;
                case RETRIEVAL -> InputOrigin.EXTERNAL;
            };
            AnnotatedContent ann = pipeline.process(entry.content(), origin, tokenBudget);
            Observation o = new Observation(
                ann.content().text(), entry.origin(),
                ann.trustTier(), entry.taintLabel(),    // taint from WM entry preserved
                ann.timestamp(), ann.sourceReference());
            o = grounding.ground(o, ctx);               // IC3: ground all obs
            obs.add(o);
            ctx.workingMemory().markProcessed(entry.id());
        }

        // ── 2. Long-term memory retrieval ────────────────────────────────
        String query = buildQuery(ctx, obs);
        if (query != null && !query.isBlank()) {
            RequestContext reqCtx = ctx.requestContext();
            memory.retrieve(MemoryQuery.text(query), 5, reqCtx).forEach(r -> {
                // M3 fix: carry the MemoryRecord's taint into the Observation
                TaintLabel taint = r.taintLabel() != null ? r.taintLabel() : TaintLabel.CLEAN;
                Observation o = new Observation(
                    r.content().text(), Origin.MEMORY,
                    TrustTier.MEDIUM, taint,
                    Instant.now(), "memory:" + r.id());
                obs.add(grounding.ground(o, ctx));      // IC3: ground memory obs too
            });
        }

        // ── 3. RAG retrieval ─────────────────────────────────────────────
        if (query != null && !query.isBlank()) {
            ragService.retrieve(new RagQuery(query, 3, Map.of())).forEach(p -> {
                // RAG passages come from external sources — label as EXTERNAL taint
                Observation o = new Observation(
                    "[" + p.sourceId() + "] " + p.text(),
                    Origin.RETRIEVAL, TrustTier.MEDIUM, TaintLabel.EXTERNAL,
                    Instant.now(), p.sourceId());
                obs.add(grounding.ground(o, ctx));      // IC3: ground RAG obs too
            });
        }

        // ── 4. Relevance filter ───────────────────────────────────────────
        Goal goal = ctx.goalStack().current().orElse(null);
        List<Observation> filtered = relevanceFilter.filter(obs, goal);

        return Observations.of(filtered);
    }

    private String buildQuery(ExecutionContext ctx, List<Observation> obs) {
        return ctx.goalStack().current().map(g ->
            g.description() + " " +
            obs.stream().map(Observation::content).limit(3).collect(Collectors.joining(" "))
        ).orElse(null);
    }
}
