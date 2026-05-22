package com.agentframework.core;
import com.agentframework.foundation.*;
/** Default validator: approves everything. Replace for production rule enforcement. */
public class PassThroughPlanValidator implements PlanValidator {
    public ValidationResult validate(Decision d, ExecutionContext ctx) {
        return new ValidationResult.Passed();
    }
    public ValidationResult validateAfterAction(ActionResult r, ExecutionContext ctx) {
        return new ValidationResult.Passed();
    }
}
