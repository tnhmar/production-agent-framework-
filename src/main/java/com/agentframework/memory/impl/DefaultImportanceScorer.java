package com.agentframework.memory.impl;
import com.agentframework.memory.*;
import java.time.temporal.ChronoUnit;
public class DefaultImportanceScorer implements ImportanceScorer {
    private static final double W_R=0.35,W_F=0.20,W_S=0.20,W_C=0.15,W_V=0.10,LAMBDA=0.02;
    public double score(MemoryRecord r, EvaluationContext ctx) {
        long ageDays = ChronoUnit.DAYS.between(r.meta().createdAt(), ctx.now());
        double recency   = W_R * Math.exp(-LAMBDA * ageDays);
        double frequency = W_F * Math.log1p(r.accessCount()) / Math.log(101);
        double salience  = W_S * r.meta().importanceScore();
        double source    = W_C * sourceWeight(r.meta().source());
        double relevance = 0;
        if (ctx.currentGoal() != null &&
            r.content().text().toLowerCase().contains(ctx.currentGoal().description().toLowerCase()))
            relevance = W_V;
        return recency + frequency + salience + source + relevance;
    }
    private double sourceWeight(String src) {
        return switch (src != null ? src : "") {
            case "tool:database","db" -> 0.9;
            case "tool:api","api"     -> 0.8;
            case "user"               -> 0.7;
            default                   -> 0.5;
        };
    }
}
