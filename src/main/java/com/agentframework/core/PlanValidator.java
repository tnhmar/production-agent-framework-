package com.agentframework.core;
import com.agentframework.foundation.*;
public interface PlanValidator {
    ValidationResult validate(Decision decision, ExecutionContext ctx);
    ValidationResult validateAfterAction(ActionResult result, ExecutionContext ctx);
}
