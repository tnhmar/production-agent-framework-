package com.agentframework.reasoning;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.*;
public class LLMReasoning implements Reasoning {
    private final LLMProvider      llm;
    private final ReasoningStrategy strategy;
    private final PromptBuilder    promptBuilder;

    public LLMReasoning(LLMProvider llm, ReasoningStrategy strategy, PromptBuilder promptBuilder) {
        this.llm=llm; this.strategy=strategy; this.promptBuilder=promptBuilder;
    }
    public Decision decide(ExecutionContext ctx, Observations obs) {
        Prompt p = promptBuilder.build(ctx, obs, strategy);
        ctx.transitionTo(com.agentframework.foundation.RunState.MODEL_CALL);
        Decision d = strategy.decide(llm, p);
        ctx.transitionTo(com.agentframework.foundation.RunState.PLANNING);
        return d;
    }
}
