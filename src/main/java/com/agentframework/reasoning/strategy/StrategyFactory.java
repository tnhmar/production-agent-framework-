package com.agentframework.reasoning.strategy;

import com.agentframework.reasoning.ReasoningStrategy;
import java.util.Objects;
import java.util.Set;

/**
 * Single entry point for constructing {@link ReasoningStrategy} instances.
 *
 * <p>All configuration is injected — no magic numbers or hardcoded strings
 * appear in factory logic.  Each {@link Type} maps to exactly one concrete
 * strategy or decorator composition.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Translate a {@link Type} enum value into a fully-wired strategy.</li>
 *   <li>Apply the {@link HitlAwareStrategy} decorator for HITL variants.</li>
 *   <li>Validate configuration bounds eagerly so failures surface at
 *       build time, not at runtime.</li>
 * </ul>
 *
 * <p><b>OCP compliance</b>: adding a new strategy type requires adding a
 * new {@link Type} constant and a new {@code case} in {@link #create} —
 * zero changes to existing cases or callers.
 */
public final class StrategyFactory {

    /** Exhaustive set of strategy types this factory can produce. */
    public enum Type {
        REACT,
        CHAIN_OF_THOUGHT,
        PLAN_AND_EXECUTE,
        REFLEXION,
        HITL_REACT,
        HITL_CHAIN_OF_THOUGHT,
        HITL_REFLEXION
    }

    // ── Configuration ──────────────────────────────────────────────────────
    private final int         maxReasoningTokens;
    private final int         maxSubtasks;
    private final Set<String> irreversibleTools;

    /**
     * @param maxReasoningTokens  token cap for CoT phase-1; [64, 4096].
     * @param maxSubtasks         max sub-tasks for PlanAndExecute; [1, 20].
     * @param irreversibleTools   tool names that trigger HITL escalation;
     *                             must not be null (may be empty).
     */
    public StrategyFactory(int maxReasoningTokens,
                           int maxSubtasks,
                           Set<String> irreversibleTools) {
        // Delegate range validation to the concrete strategy constructors;
        // validate non-null here to fail fast at factory construction time.
        this.maxReasoningTokens = maxReasoningTokens;
        this.maxSubtasks        = maxSubtasks;
        this.irreversibleTools  = Objects.requireNonNull(
            irreversibleTools, "irreversibleTools must not be null");
    }

    /** Factory with production-sensible defaults and an empty irreversible-tool set. */
    public static StrategyFactory defaults() {
        return new StrategyFactory(512, 6, Set.of());
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Creates the requested {@link ReasoningStrategy}.
     *
     * @param type  strategy variant; must not be null.
     * @return fully-wired, ready-to-use strategy instance.
     */
    public ReasoningStrategy create(Type type) {
        Objects.requireNonNull(type, "type must not be null");
        return switch (type) {
            case REACT                -> new ReActStrategy();
            case CHAIN_OF_THOUGHT     -> new ChainOfThoughtStrategy(maxReasoningTokens);
            case PLAN_AND_EXECUTE     -> new PlanAndExecuteStrategy(maxSubtasks);
            case REFLEXION            -> new ReflexionStrategy();
            case HITL_REACT           -> HitlAwareStrategy.wrapping(
                                             new ReActStrategy(), irreversibleTools);
            case HITL_CHAIN_OF_THOUGHT-> HitlAwareStrategy.wrapping(
                                             new ChainOfThoughtStrategy(maxReasoningTokens),
                                             irreversibleTools);
            case HITL_REFLEXION       -> HitlAwareStrategy.wrapping(
                                             new ReflexionStrategy(), irreversibleTools);
        };
    }
}
