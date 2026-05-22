package com.agentframework.tests;
import com.agentframework.foundation.*;
import com.agentframework.testutil.Assert;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
public class FoundationTest {

    public void testSealedDecisionHierarchy() {
        Decision tc  = new ToolCall("search", Map.of("q","java"), "searching");
        Decision fa  = new FinalAnswer("42", List.of());
        Decision esc = new Escalate("too hard","HIGH");
        Decision ask = new AskClarification("what do you mean?");
        Decision par = new ParallelToolCalls(List.of((ToolCall)tc), true, Duration.ofSeconds(5));
        // exhaustive switch must compile
        String kind = switch (tc) {
            case ToolCall t        -> "tool";
            case FinalAnswer f     -> "final";
            case Escalate e        -> "escalate";
            case AskClarification a-> "clarify";
            case ParallelToolCalls p-> "parallel";
        };
        Assert.assertEquals("tool", kind, "ToolCall dispatch");
        Assert.assertEquals("42", ((FinalAnswer)fa).content(), "FinalAnswer content");
        Assert.assertEquals("HIGH", ((Escalate)esc).severity(), "Escalate severity");
    }

    public void testActionResultSealed() {
        ActionResult s = ActionResult.success(ToolResult.ok("hello"));
        ActionResult f = ActionResult.failure("ERR","oops");
        Assert.assertTrue(s.isSuccess(), "Success isSuccess");
        Assert.assertFalse(f.isSuccess(), "Failure not isSuccess");
        Assert.assertFalse(s.indicatesWorldChange(), "READ_ONLY no world change");
    }

    public void testToolResultWorldChange() {
        ToolResult ro  = ToolResult.ok("data");
        ToolResult wr  = ToolResult.write("data");
        Assert.assertFalse(ro.indicatesWorldChange(), "READ_ONLY");
        Assert.assertTrue(wr.indicatesWorldChange(), "WRITE");
    }

    public void testValidationVerdict() {
        ValidationVerdict ok  = ValidationVerdict.ok();
        ValidationVerdict bad = ValidationVerdict.failed("bad input");
        ValidationVerdict app = ValidationVerdict.requireApproval("needs human");
        Assert.assertTrue(ok.isPassed(), "ok passes");
        Assert.assertFalse(bad.isPassed(), "failed does not pass");
        Assert.assertFalse(app.isPassed(), "requireApproval does not pass");
        Assert.assertTrue(app.requiresApproval(), "requireApproval flag");
        Assert.assertEquals("bad input", bad.reason(), "reason preserved");
    }

    public void testRunStateTerminal() {
        Assert.assertTrue(RunState.COMPLETED.isTerminal(),  "COMPLETED terminal");
        Assert.assertTrue(RunState.ABORTED.isTerminal(),    "ABORTED terminal");
        Assert.assertTrue(RunState.TERMINATED.isTerminal(), "TERMINATED terminal");
        Assert.assertFalse(RunState.PLANNING.isTerminal(),  "PLANNING not terminal");
        Assert.assertFalse(RunState.INITIALIZED.isTerminal(),"INITIALIZED not terminal");
    }

    public void testTerminationReasonSealed() {
        TerminationReason gc = new TerminationReason.GoalCompleted();
        TerminationReason es = new TerminationReason.Escalated("too hard");
        TerminationReason rl = new TerminationReason.ResourceLimit("max tokens");
        TerminationReason fe = new TerminationReason.FailureEscalation("5 failures");
        String r = switch (gc) {
            case TerminationReason.GoalCompleted g   -> "done";
            case TerminationReason.Escalated e       -> "escalated";
            case TerminationReason.ResourceLimit l   -> "limit";
            case TerminationReason.FailureEscalation f -> "fail";
        };
        Assert.assertEquals("done", r, "GoalCompleted match");
    }

    public void testTaskBuilder() {
        Task t = Task.builder()
            .instruction("do X").maxCycles(10).maxTokens(5000)
            .maxWallClockTime(Duration.ofMinutes(2))
            .budgetLimit(BigDecimal.valueOf(50))
            .build();
        Assert.assertEquals("do X", t.instruction(), "instruction");
        Assert.assertEquals(10, t.maxCycles(), "maxCycles");
    }

    public void testTaskRequiresInstruction() {
        Assert.assertThrows(NullPointerException.class,
            () -> Task.builder().build(), "null instruction");
    }

    public void testTokenEstimatorHeuristic() {
        TokenEstimator est = TokenEstimator.heuristic();
        String text = "hello world foo bar";
        int est1 = est.estimate(text);
        Assert.assertTrue(est1 > 0, "estimate > 0");
        String trunc = est.truncate(text, 2);
        Assert.assertTrue(trunc.length() <= text.length(), "truncate shorter");
    }
}
