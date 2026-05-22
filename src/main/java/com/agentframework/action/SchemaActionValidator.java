package com.agentframework.action;
import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.*;
/** Validates tool arguments against the contract's input schema (stub; pluggable). */
public class SchemaActionValidator implements ActionValidator {
    public ValidationVerdict validate(ToolCall call, ToolContract contract, ExecutionContext ctx) {
        if (contract == null)
            return ValidationVerdict.failed("No contract found for tool: " + call.toolName());
        if (call.arguments() == null)
            return ValidationVerdict.failed("null arguments for: " + call.toolName());
        return ValidationVerdict.ok();
    }
}
