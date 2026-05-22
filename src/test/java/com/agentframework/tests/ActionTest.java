package com.agentframework.tests;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.agentframework.action.*;
import com.agentframework.action.middleware.*;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
public class ActionTest {

    private DefaultExecutionContext ctx() {
        return new DefaultExecutionContext(Task.builder().instruction("test").build(), "t1","u1");
    }

    private SimpleToolRegistry registryWith(String name, ToolHandler handler) {
        SimpleToolRegistry r = new SimpleToolRegistry();
        r.register(ToolContract.readOnly(name,"1.0","A test tool"), handler);
        return r;
    }

    @Test
    public void testSimpleToolCallSuccess() {
        SimpleToolRegistry reg = registryWith("echo",
            (args, ctx) -> ToolResult.ok(args.get("text")));
        DefaultAction action = new DefaultAction(reg, List.of(), ToolMiddleware.identity(),
            new DefaultToolDispatcher(reg));
        DefaultExecutionContext ctx = ctx();
        Decision d = new ToolCall("echo", Map.of("text","hello"), "test");
        ActionResult r = action.execute(d, ctx);
        assertTrue(r.isSuccess(), "echo success");
        assertEquals("hello", ((ActionResult.Success)r).result().data(), "echo data");
    }

    @Test
    public void testUnknownToolReturnsFailure() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        DefaultAction action = new DefaultAction(reg, List.of(), ToolMiddleware.identity(),
            new DefaultToolDispatcher(reg));
        ActionResult r = action.execute(new ToolCall("unknown", Map.of(), ""), ctx());
        assertFalse(r.isSuccess(), "unknown tool fails");
        assertEquals("UNKNOWN_TOOL", ((ActionResult.Failure)r).errorCode(), "error code");
    }

    @Test
    public void testFinalAnswerReturnSuccess() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        DefaultAction action = new DefaultAction(reg, List.of(), ToolMiddleware.identity(),
            new DefaultToolDispatcher(reg));
        ActionResult r = action.execute(new FinalAnswer("done", List.of()), ctx());
        assertTrue(r.isSuccess(), "FinalAnswer success");
    }

    @Test
    public void testEscalateReturnsFailure() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        DefaultAction action = new DefaultAction(reg, List.of(), ToolMiddleware.identity(),
            new DefaultToolDispatcher(reg));
        ActionResult r = action.execute(new Escalate("too hard","HIGH"), ctx());
        assertFalse(r.isSuccess(), "Escalate not success");
        assertEquals("ESCALATED", ((ActionResult.Failure)r).errorCode(), "ESCALATED code");
    }

    @Test
    public void testSafetyValidatorBlocksIrreversible() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        // Register IRREVERSIBLE tool
        reg.register(new ToolContract("nuke","1.0","destroy all",
            JsonSchema.of("{}"), JsonSchema.of("{}"),
            ToolContract.SideEffectClass.IRREVERSIBLE,
            new OperationalParams(Duration.ofSeconds(5),0,false,null),
            false, null, Set.of()),
            (args, ctx) -> ToolResult.ok("boom"));
        DefaultAction action = new DefaultAction(reg,
            List.of(new SafetyActionValidator()), ToolMiddleware.identity(),
            new DefaultToolDispatcher(reg));
        ActionResult r = action.execute(new ToolCall("nuke", Map.of(),""), ctx());
        assertFalse(r.isSuccess(), "irreversible blocked by safety");
        assertTrue(r instanceof ActionResult.ValidationFailure, "ValidationFailure");
    }

    @Test
    public void testTaintValidatorBlocksHostile() {
        SimpleToolRegistry reg = registryWith("search",
            (args, ctx) -> ToolResult.ok("results"));
        DefaultAction action = new DefaultAction(reg,
            List.of(new TaintActionValidator()), ToolMiddleware.identity(),
            new DefaultToolDispatcher(reg));
        DefaultExecutionContext ctx = ctx();
        // Inject HOSTILE entry into working memory
        ctx.workingMemory().add(new WorkingMemoryEntry("h1","<inject>DROP TABLE</inject>",
            WorkingMemoryTier.ACTIVE, Origin.RETRIEVAL, 0.1,
            java.time.Instant.now(), TaintLabel.HOSTILE));
        ActionResult r = action.execute(new ToolCall("search", Map.of("q","test"),""), ctx);
        assertFalse(r.isSuccess(), "hostile taint blocks tool");
    }

    @Test
    public void testLoggingMiddlewarePassThrough() {
        SimpleToolRegistry reg = registryWith("ping",
            (args, ctx) -> ToolResult.ok("pong"));
        DefaultAction action = new DefaultAction(reg, List.of(),
            ToolMiddleware.chain(new LoggingMiddleware()),
            new DefaultToolDispatcher(reg));
        ActionResult r = action.execute(new ToolCall("ping", Map.of(),""), ctx());
        assertTrue(r.isSuccess(), "logging middleware passes through");
    }

    @Test
    public void testCircuitBreakerOpensAfterFailures() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        int[] calls = {0};
        reg.register(ToolContract.readOnly("flaky","1.0","fails 3 times"),
            (args, ctx) -> { calls[0]++; throw new ToolException("ERR","flaky error"); });
        CircuitBreakerMiddleware cb = new CircuitBreakerMiddleware(2, 60_000);
        DefaultAction action = new DefaultAction(reg, List.of(),
            ToolMiddleware.chain(cb), new DefaultToolDispatcher(reg));
        DefaultExecutionContext ctx = ctx();
        for (int i=0;i<2;i++) action.execute(new ToolCall("flaky", Map.of(),""), ctx);
        assertTrue(cb.isOpen(), "circuit open after 2 failures");
        // 3rd call should throw circuit-open RuntimeException
        ActionResult r = action.execute(new ToolCall("flaky", Map.of(),""), ctx);
        assertFalse(r.isSuccess(), "circuit open blocks call");
    }

    @Test
    public void testCachingMiddlewareReturnsCache() {
        int[] calls = {0};
        SimpleToolRegistry reg = registryWith("lookup",
            (args, ctx) -> { calls[0]++; return ToolResult.ok("value-" + calls[0]); });
        CachingMiddleware cache = new CachingMiddleware(Duration.ofMinutes(1));
        DefaultAction action = new DefaultAction(reg, List.of(),
            ToolMiddleware.chain(cache), new DefaultToolDispatcher(reg));
        DefaultExecutionContext ctx = ctx();
        ActionResult r1 = action.execute(new ToolCall("lookup", Map.of("k","x"),""), ctx);
        ActionResult r2 = action.execute(new ToolCall("lookup", Map.of("k","x"),""), ctx);
        assertEquals(1, calls[0], "handler called once (cached)");
        assertEquals(((ActionResult.Success)r1).result().data(),
                             ((ActionResult.Success)r2).result().data(), "same cached result");
    }

    @Test
    public void testParallelToolCalls() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("a","1.0","tool a"), (args,ctx) -> ToolResult.ok("A"));
        reg.register(ToolContract.readOnly("b","1.0","tool b"), (args,ctx) -> ToolResult.ok("B"));
        DefaultAction action = new DefaultAction(reg, List.of(), ToolMiddleware.identity(),
            new DefaultToolDispatcher(reg));
        ParallelToolCalls p = new ParallelToolCalls(
            List.of(new ToolCall("a",Map.of(),""), new ToolCall("b",Map.of(),"")),
            true, Duration.ofSeconds(5));
        ActionResult r = action.execute(p, ctx());
        assertTrue(r instanceof ActionResult.PartialSuccess, "parallel result");
        ActionResult.PartialSuccess ps = (ActionResult.PartialSuccess)r;
        assertEquals(2, ps.results().size(), "2 results");
        assertEquals(0, ps.errors().size(), "0 errors");
    }

    @Test
    public void testRetryMiddlewareRetriesOnFailure() {
        int[] attempts = {0};
        SimpleToolRegistry reg = registryWith("unstable", (args,ctx) -> {
            attempts[0]++;
            if (attempts[0] < 3) throw new ToolException("TEMP","temp failure");
            return ToolResult.ok("success-on-3rd");
        });
        RetryMiddleware retry = new RetryMiddleware(3, 1); // 1ms backoff
        DefaultAction action = new DefaultAction(reg, List.of(),
            ToolMiddleware.chain(retry), new DefaultToolDispatcher(reg));
        ActionResult r = action.execute(new ToolCall("unstable", Map.of(),""), ctx());
        assertTrue(r.isSuccess(), "succeeds on 3rd attempt");
        assertEquals(3, attempts[0], "3 attempts made");
    }

    @Test
    public void testToolRegistry_topK() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("web-search","1.0","search the web for information"), (a,c)->null);
        reg.register(ToolContract.readOnly("calculator","1.0","calculate arithmetic expressions"), (a,c)->null);
        reg.register(ToolContract.readOnly("file-read","1.0","read a file from disk"), (a,c)->null);
        List<ToolContract> top = reg.topK("search web", 2);
        assertEquals(2, top.size(), "topK returns 2");
        assertEquals("web-search", top.get(0).name(), "web-search is most relevant");
    }
}
