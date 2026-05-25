package com.agentframework.core;

import com.agentframework.foundation.*;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;

/**
 * Chains multiple {@link PlanValidator} instances in order.
 * The first non-Passed result short-circuits the chain.
 *
 * <p>Usage:
 * <pre>{@code
 *   PlanValidator v = new CompositePlanValidator(List.of(
 *       new GoalCoherencePlanValidator(),
 *       new MyDomainPolicyValidator()
 *   ));
 *   new AgentRuntime(v, events);
 * }</pre>
 */
public class CompositePlanValidator implements PlanValidator {

    private final List<PlanValidator> validators;

    @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
    public CompositePlanValidator(List<PlanValidator> validators) {
        if (validators == null || validators.isEmpty())
            throw new IllegalArgumentException("At least one PlanValidator is required");
        this.validators = List.copyOf(validators);
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
