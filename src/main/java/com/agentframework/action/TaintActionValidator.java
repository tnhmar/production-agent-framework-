package com.agentframework.action;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import java.util.List;
/** Blocks tool calls originating from HOSTILE-tainted working-memory entries. */
public class TaintActionValidator implements ActionValidator {
    public ValidationVerdict validate(ToolCall call, ToolContract contract, ExecutionContext ctx) {
        List<WorkingMemoryEntry> hostile = ctx.workingMemory().getAll().stream()
            .filter(e -> e.taintLabel() == TaintLabel.HOSTILE)
            .toList();
        if (!hostile.isEmpty())
            return ValidationVerdict.failed("Hostile-tainted input detected; tool call blocked");
        return ValidationVerdict.ok();
    }
}
