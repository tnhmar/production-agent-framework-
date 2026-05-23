package com.agentframework.core;
import com.agentframework.foundation.*;
import java.util.List; import java.util.Optional;
public class GoalCoherencePlanValidator implements PlanValidator {
    @Override public ValidationResult validate(Decision d, ExecutionContext ctx) {
        if (d instanceof Escalate || d instanceof AskClarification) return new ValidationResult.Passed();
        Optional<Goal> root=ctx.goalStack().all().stream().filter(g->"root".equals(g.id())).findFirst();
        if (root.isEmpty()) return new ValidationResult.Failed("No root goal",List.of());
        GoalStatus s=root.get().status();
        if (s==GoalStatus.COMPLETED) return new ValidationResult.Failed("Root COMPLETED",List.of());
        if (s==GoalStatus.FAILED)    return new ValidationResult.Failed("Root FAILED",List.of());
        if (d instanceof FinalAnswer) return new ValidationResult.Passed();
        if (d instanceof ToolCall tc) {
            Goal cur=ctx.goalStack().current().orElse(root.get());
            String c=cur.successCriteria();
            if (c!=null&&!c.isBlank()&&c.toLowerCase().contains("!"+tc.toolName().toLowerCase()))
                return new ValidationResult.NeedsCorrection("Tool excluded",null);
        }
        return new ValidationResult.Passed();
    }
    @Override public ValidationResult validateAfterAction(ActionResult r, ExecutionContext ctx) {
        if (!(r instanceof ActionResult.Success)) return new ValidationResult.Passed();
        boolean bad=ctx.goalStack().all().stream().filter(g->"root".equals(g.id()))
            .anyMatch(g->g.status()==GoalStatus.FAILED);
        return bad?new ValidationResult.NeedsCorrection("Root FAILED after action",null):new ValidationResult.Passed();
    }
}
