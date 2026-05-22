package com.agentframework.tests;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.observability.*;
import com.agentframework.perception.*;
import com.agentframework.testutil.Assert;
import java.time.Instant;
import java.util.Map;
public class ObservabilityPerceptionTest {

    public void testInMemoryEventSink() {
        InMemoryEventSink sink = new InMemoryEventSink();
        Assert.assertEquals(0, sink.total(), "empty");
        sink.emit(new AgentEvent("r1","t1", AgentEvent.EventType.RUN_STARTED, Instant.now(), Map.of()));
        sink.emit(new AgentEvent("r1","t1", AgentEvent.EventType.CYCLE_STARTED, Instant.now(), Map.of("cycle",1)));
        sink.emit(new AgentEvent("r1","t1", AgentEvent.EventType.CYCLE_COMPLETED, Instant.now(), Map.of("cycle",1)));
        Assert.assertEquals(3, sink.total(), "3 events");
        Assert.assertEquals(1, sink.count(AgentEvent.EventType.RUN_STARTED), "1 RUN_STARTED");
        Assert.assertEquals(1, sink.count(AgentEvent.EventType.CYCLE_STARTED), "1 CYCLE_STARTED");
    }

    public void testNoopEventSink() {
        EventSink noop = EventSink.noop();
        // should not throw
        noop.emit(new AgentEvent("r","t", AgentEvent.EventType.RUN_ABORTED, Instant.now(), Map.of()));
    }

    public void testRunMetricsSnapshot() {
        DefaultExecutionContext ctx = new DefaultExecutionContext(
            Task.builder().instruction("x").build(),"t1","u1");
        ctx.addTokens(500); ctx.addCost(java.math.BigDecimal.valueOf(2));
        ctx.incrementCycle();
        RunMetrics metrics = new RunMetrics(ctx.runId());
        metrics.recordToolCall(); metrics.recordToolCall(); metrics.recordToolFailure();
        MetricsSnapshot snap = metrics.snapshot(ctx);
        Assert.assertEquals(1, snap.cycles(), "1 cycle");
        Assert.assertEquals(500, snap.totalTokens(), "500 tokens");
        Assert.assertEquals(2, snap.toolCalls(), "2 tool calls");
        Assert.assertEquals(1, snap.toolFailures(), "1 failure");
    }

    public void testSimplePerceptionDrainsWorkingMemory() {
        DefaultExecutionContext ctx = new DefaultExecutionContext(
            Task.builder().instruction("test").build(),"t1","u1");
        ctx.workingMemory().add(new WorkingMemoryEntry("e1","user input",
            WorkingMemoryTier.ACTIVE, Origin.USER, 1.0, Instant.now(), TaintLabel.CLEAN));
        ctx.workingMemory().add(new WorkingMemoryEntry("e2","tool output",
            WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.9, Instant.now(), TaintLabel.CLEAN));
        SimplePerception p = new SimplePerception();
        Observations obs = p.perceive(ctx);
        Assert.assertEquals(2, obs.items().size(), "2 observations");
        long unprocessed = ctx.workingMemory().getUnprocessed().stream()
            .filter(e -> e.origin()==Origin.USER || e.origin()==Origin.TOOL)
            .count();
        Assert.assertEquals(0L, unprocessed, "all processed");
    }

    public void testSimplePerceptionUsesGoalWhenEmpty() {
        DefaultExecutionContext ctx = new DefaultExecutionContext(
            Task.builder().instruction("test").build(),"t1","u1");
        ctx.goalStack().push(new Goal("root",null,GoalStatus.PENDING,
            "describe the weather",java.util.List.of(),Budget.unlimited()));
        SimplePerception p = new SimplePerception();
        Observations obs = p.perceive(ctx);
        Assert.assertEquals(1, obs.items().size(), "goal as observation");
        Assert.assertContains(obs.items().get(0).content(), "weather", "goal content");
    }

    public void testGroundingServiceIdentity() {
        GroundingService g = GroundingService.identity();
        Observation o = new Observation("test", Origin.USER, TrustTier.HIGH,
            Instant.now(), "src");
        Observation grounded = g.ground(o, null);
        Assert.assertEquals(o.content(), grounded.content(), "identity preserves content");
    }

    public void testRelevanceFilterPassThrough() {
        RelevanceFilter f = RelevanceFilter.passThrough();
        java.util.List<Observation> obs = java.util.List.of(
            new Observation("a", Origin.USER, TrustTier.HIGH, Instant.now(), "src"),
            new Observation("b", Origin.TOOL, TrustTier.MEDIUM, Instant.now(), "src"));
        java.util.List<Observation> result = f.filter(obs, null);
        Assert.assertEquals(2, result.size(), "pass-through preserves all");
    }
}
