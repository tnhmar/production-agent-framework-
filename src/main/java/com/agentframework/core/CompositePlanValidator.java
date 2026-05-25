package com.agentframework.core;

import com.agentframework.foundation.*;
import java.util.List;

/**
 * Chains multiple {@link PlanValidator} instances in order.
 * The first non-Passed result short-circuits the chain.
 *
 * <p>Usage:
 * <pre>{@code
 *   PlanValidator v = CompositePlanValidator.of(
 *       new GoalCoherencePlanValidator(),
 *       new MyDomainPolicyValidator()
 *   );
 *   new AgentRuntime(v, events);
 * }</pre>
 *
 * <p>Use the static factory {@link #of(List)} or {@link #of(PlanValidator...)} instead
 * of the constructor directly — validation is performed there so the constructor
 * never throws (avoids CT_CONSTRUCTOR_THROW).
 */
public class CompositePlanValidator implements PlanValidator {

    private final List<PlanValidator> validators;

    /** Use {@link #of(List)} or {@link #of(PlanValidator...)} instead. */
    private CompositePlanValidator(List<PlanValidator> validators) {
        this.validators = validators;
    }

    /** Factory — validates arguments before constructing. */
    public static CompositePlanValidator of(List<PlanValidator> validators) {
        if (validators == null || validators.isEmpty())
            throw new IllegalArgumentException("At least one PlanValidator is required");
        return new CompositePlanValidator(List.copyOf(validators));
    }

    /** Varargs convenience factory. */
    @SafeVarargs
    public static CompositePlanValidator of(PlanValidator... validators) {
        return of(List.of(validators));
    }

    @Override
    public ValidationResult validate(Decision decision, ExecutionContext ctx) {
        for (PlanValidator v : validators) {
            ValidationResult r = v.validate(decision, ctx);
            if (!(r instanceof ValidationResult.Passed)) return r;
        }
        return new ValidationResult.Passed();
    }

    @Override
    public ValidationResult validateAfterAction(ActionResult result, ExecutionContext ctx) {
        for (PlanValidator v : validators) {
            ValidationResult r = v.validateAfterAction(result, ctx);
            if (!(r instanceof ValidationResult.Passed)) return r;
        }
        return new ValidationResult.Passed();
    }
}
