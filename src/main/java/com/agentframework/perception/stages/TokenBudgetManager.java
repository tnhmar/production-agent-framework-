package com.agentframework.perception.stages;
import com.agentframework.foundation.TokenEstimator;
public class TokenBudgetManager implements BudgetManager {
    private final TokenEstimator estimator;
    public TokenBudgetManager(TokenEstimator estimator) { this.estimator = estimator; }
    public AnnotatedContent enforceTokenBudget(AnnotatedContent content, int maxTokens) {
        if (maxTokens <= 0) return content;
        String text = content.content().text();
        if (estimator.estimate(text) <= maxTokens) return content;
        String truncated = estimator.truncate(text, maxTokens);
        return new AnnotatedContent(
            new NormalizedContent(truncated, content.content().structured()),
            content.origin(), content.trustTier(), content.timestamp(), content.sourceReference());
    }
}
