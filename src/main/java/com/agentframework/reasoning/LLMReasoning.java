package com.agentframework.reasoning;

import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.Decision;
import com.agentframework.foundation.Observations;

/**
 * {@link Reasoning} implementation that delegates to a {@link ReasoningStrategy}
 * via an injected {@link LLMProvider}.
 *
 * <h3>M1 fix — removed double state transitions</h3>
 * <p>{@code StateMachineRunner} (PLANNING case) already calls:
 * <pre>
 *   ctx.transitionTo(RunState.MODEL_CALL);
 *   Decision d = agent.reasoning().decide(ctx, obs);
 *   ctx.transitionTo(RunState.PLANNING);
 * </pre>
 * This class previously duplicated those two transitions internally, causing
 * them to fire twice per cycle.  The SRP violation has been corrected:
 * {@code LLMReasoning} is now a pure delegator — it assembles the prompt and
 * calls the strategy; state-machine management belongs exclusively to
 * {@code StateMachineRunner}.
 */
public class LLMReasoning implements Reasoning {

    private final LLMProvider       llm;
    private final ReasoningStrategy strategy;
    private final PromptBuilder     promptBuilder;

    public LLMReasoning(LLMProvider llm, ReasoningStrategy strategy,
                        PromptBuilder promptBuilder) {
        this.llm           = llm;
        this.strategy      = strategy;
        this.promptBuilder = promptBuilder;
    }

    /**
     * Assembles the prompt from the current context and observations, then
     * delegates to the strategy for the actual LLM call.
     *
     * <p>Does NOT call {@code ctx.transitionTo()} — state transitions are the
     * sole responsibility of {@code StateMachineRunner} (M1 fix).
     */
    @Override
    public Decision decide(ExecutionContext ctx, Observations obs) {
        Prompt p = promptBuilder.build(ctx, obs, strategy);
        return strategy.decide(llm, p);
    }
}
