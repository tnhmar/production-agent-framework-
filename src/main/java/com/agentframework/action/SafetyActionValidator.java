package com.agentframework.action;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.*;

/** Flags irreversible and high-blast-radius actions for human approval. */
public class SafetyActionValidator implements ActionValidator {
    public ValidationVerdict validate(ToolCall call, ToolContract contract, ExecutionContext ctx) {
        if (contract == null) return ValidationVerdict.ok();
        return switch (contract.sideEffect()) {
            case IRREVERSIBLE      -> ValidationVerdict.requireApproval(
                "Irreversible action requires human approval: " + call.toolName());
            case HIGH_BLAST_RADIUS -> ValidationVerdict.requireApproval(
                "High-blast-radius action requires staged execution approval: " + call.toolName());
            default -> ValidationVerdict.ok();
        };
    }
}
