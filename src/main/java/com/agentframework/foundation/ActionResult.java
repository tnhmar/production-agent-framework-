package com.agentframework.foundation;
import java.util.List;
public sealed interface ActionResult
    permits ActionResult.Success, ActionResult.Failure, ActionResult.ValidationFailure,
            ActionResult.PartialSuccess, ActionResult.Escalated, ActionResult.Clarification {

    record Success(ToolResult result)                              implements ActionResult {}
    record Failure(String errorCode, String message)              implements ActionResult {}
    record ValidationFailure(ValidationVerdict verdict)           implements ActionResult {}
    record PartialSuccess(List<ToolResult> results,
                          List<String> errors)                    implements ActionResult {}
    record Escalated(String reason, String level)                 implements ActionResult {}
    record Clarification(String question)                         implements ActionResult {}

    static Success        success(ToolResult r)               { return new Success(r); }
    static Failure        failure(String code, String msg)    { return new Failure(code, msg); }
    static ValidationFailure validationFailure(ValidationVerdict v){ return new ValidationFailure(v); }
    static PartialSuccess partial(List<ToolResult> r, List<String> e){ return new PartialSuccess(r,e); }

    default boolean isSuccess()        { return this instanceof Success; }
    default boolean isFatalFailure()   { return this instanceof Failure f
        && List.of("ABORTED","INTERRUPTED","CIRCUIT_OPEN").contains(f.errorCode()); }
    default boolean indicatesWorldChange() {
        return this instanceof Success s && s.result().indicatesWorldChange();
    }
}
