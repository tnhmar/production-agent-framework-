package com.agentframework.action;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.*;
/** Flags irreversible actions for human approval. */
public class SafetyActionValidator implements ActionValidator {
    public ValidationVerdict validate(ToolCall call, ToolContract contract, ExecutionContext ctx) {
        if (contract != null &&
            contract.sideEffect() == ToolContract.SideEffectClass.IRREVERSIBLE)
            return ValidationVerdict.requireApproval("Irreversible action requires human approval");
        return ValidationVerdict.ok();
    }
}
