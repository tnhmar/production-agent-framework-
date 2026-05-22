package com.agentframework.tests;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.agentframework.foundation.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
public class FoundationTest {

    @Test
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
        assertEquals("tool", kind, "ToolCall dispatch");
        assertEquals("42", ((FinalAnswer)fa).content(), "FinalAnswer content");
        assertEquals("HIGH", ((Escalate)esc).severity(), "Escalate severity");
    }

    @Test
    public void testActionResultSealed() {
        ActionResult s = ActionResult.success(ToolResult.ok("hello"));
        ActionResult f = ActionResult.failure("ERR","oops");
        assertTrue(s.isSuccess(), "Success isSuccess");
        assertFalse(f.isSuccess(), "Failure not isSuccess");
        assertFalse(s.indicatesWorldChange(), "READ_ONLY no world change");
    }

    @Test
    public void testToolResultWorldChange() {
        ToolResult ro  = ToolResult.ok("data");
        ToolResult wr  = ToolResult.write("data");
        assertFalse(ro.indicatesWorldChange(), "READ_ONLY");
        assertTrue(wr.indicatesWorldChange(), "WRITE");
    }

    @Test
    public void testValidationVerdict() {
        ValidationVerdict ok  = ValidationVerdict.ok();
        ValidationVerdict bad = ValidationVerdict.failed("bad input");
        ValidationVerdict app = ValidationVerdict.requireApproval("needs human");
        assertTrue(ok.isPassed(), "ok passes");
        assertFalse(bad.isPassed(), "failed does not pass");
        assertFalse(app.isPassed(), "requireApproval does not pass");
        assertTrue(app.requiresApproval(), "requireApproval flag");
        assertEquals("bad input", bad.reason(), "reason preserved");
    }

    @Test
    public void testRunStateTerminal() {
        assertTrue(RunState.COMPLETED.isTerminal(),  "COMPLETED terminal");
        assertTrue(RunState.ABORTED.isTerminal(),    "ABORTED terminal");
        assertTrue(RunState.TERMINATED.isTerminal(), "TERMINATED terminal");
        assertFalse(RunState.PLANNING.isTerminal(),  "PLANNING not terminal");
        assertFalse(RunState.INITIALIZED.isTerminal(),"INITIALIZED not terminal");
    }

    @Test
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
        assertEquals("done", r, "GoalCompleted match");
    }

    @Test
    public void testTaskBuilder() {
        Task t = Task.builder()
            .instruction("do X").maxCycles(10).maxTokens(5000)
            .maxWallClockTime(Duration.ofMinutes(2))
            .budgetLimit(BigDecimal.valueOf(50))
            .build();
        assertEquals("do X", t.instruction(), "instruction");
        assertEquals(10, t.maxCycles(), "maxCycles");
    }

    @Test
    public void testTaskRequiresInstruction() {
        assertThrows(NullPointerException.class,
            () -> Task.builder().build(), "null instruction");
    }

    @Test
    public void testTokenEstimatorHeuristic() {
        TokenEstimator est = TokenEstimator.heuristic();
        String text = "hello world foo bar";
        int est1 = est.estimate(text);
        assertTrue(est1 > 0, "estimate > 0");
        String trunc = est.truncate(text, 2);
        assertTrue(trunc.length() <= text.length(), "truncate shorter");
    }
}
