package com.agentframework.action;

import com.agentframework.core.ExecutionContext;
import com.agentframework.foundation.*;

import java.util.List;

/**
 * Validates tool arguments against the contract's declared input schema.
 *
 * <p>Checks:
 * <ol>
 *   <li>A contract must exist for the named tool.</li>
 *   <li>The arguments map must not be null.</li>
 *   <li>All required fields declared in {@code contract.inputSchema()} must be present.</li>
 *   <li>Each field present in the call must match its declared JSON Schema type.</li>
 * </ol>
 *
 * <p>The underlying {@link com.agentframework.foundation.JsonSchema#validate(java.util.Map)}
 * method handles parsing; this validator only interprets the result and converts it to
 * a {@link ValidationVerdict}.
 */
public class SchemaActionValidator implements ActionValidator {

    public ValidationVerdict validate(ToolCall call, ToolContract contract, ExecutionContext ctx) {
        if (contract == null)
            return ValidationVerdict.failed("No contract registered for tool: \"" + call.toolName() + "\"");

        if (call.arguments() == null)
            return ValidationVerdict.failed("Null arguments for tool: \"" + call.toolName() + "\"");

        List<String> violations = contract.inputSchema().validate(call.arguments());
        if (violations.isEmpty())
            return ValidationVerdict.ok();

        return ValidationVerdict.failed(
            "Schema violations for tool \"" + call.toolName() + "\": "
            + String.join("; ", violations));
    }
}
