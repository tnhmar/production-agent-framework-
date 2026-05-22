package com.agentframework.tests;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.observability.*;
import com.agentframework.perception.*;
import java.time.Instant;
import java.util.Map;
public class ObservabilityPerceptionTest {

    @Test
    public void testInMemoryEventSink() {
        InMemoryEventSink sink = new InMemoryEventSink();
        assertEquals(0, sink.total(), "empty");
        sink.emit(new AgentEvent("r1","t1", AgentEvent.EventType.RUN_STARTED, Instant.now(), Map.of()));
        sink.emit(new AgentEvent("r1","t1", AgentEvent.EventType.CYCLE_STARTED, Instant.now(), Map.of("cycle",1)));
        sink.emit(new AgentEvent("r1","t1", AgentEvent.EventType.CYCLE_COMPLETED, Instant.now(), Map.of("cycle",1)));
        assertEquals(3, sink.total(), "3 events");
        assertEquals(1, sink.count(AgentEvent.EventType.RUN_STARTED), "1 RUN_STARTED");
        assertEquals(1, sink.count(AgentEvent.EventType.CYCLE_STARTED), "1 CYCLE_STARTED");
    }

    @Test
    public void testNoopEventSink() {
        EventSink noop = EventSink.noop();
        // should not throw
        noop.emit(new AgentEvent("r","t", AgentEvent.EventType.RUN_ABORTED, Instant.now(), Map.of()));
    }

    @Test
    public void testRunMetricsSnapshot() {
        DefaultExecutionContext ctx = new DefaultExecutionContext(
            Task.builder().instruction("x").build(),"t1","u1");
        ctx.addTokens(500); ctx.addCost(java.math.BigDecimal.valueOf(2));
        ctx.incrementCycle();
        RunMetrics metrics = new RunMetrics(ctx.runId());
        metrics.recordToolCall(); metrics.recordToolCall(); metrics.recordToolFailure();
        MetricsSnapshot snap = metrics.snapshot(ctx);
        assertEquals(1, snap.cycles(), "1 cycle");
        assertEquals(500, snap.totalTokens(), "500 tokens");
        assertEquals(2, snap.toolCalls(), "2 tool calls");
        assertEquals(1, snap.toolFailures(), "1 failure");
    }

    @Test
    public void testSimplePerceptionDrainsWorkingMemory() {
        DefaultExecutionContext ctx = new DefaultExecutionContext(
            Task.builder().instruction("test").build(),"t1","u1");
        ctx.workingMemory().add(new WorkingMemoryEntry("e1","user input",
            WorkingMemoryTier.ACTIVE, Origin.USER, 1.0, Instant.now(), TaintLabel.CLEAN));
        ctx.workingMemory().add(new WorkingMemoryEntry("e2","tool output",
            WorkingMemoryTier.ACTIVE, Origin.TOOL, 0.9, Instant.now(), TaintLabel.CLEAN));
        SimplePerception p = new SimplePerception();
        Observations obs = p.perceive(ctx);
        assertEquals(2, obs.items().size(), "2 observations");
        long unprocessed = ctx.workingMemory().getUnprocessed().stream()
            .filter(e -> e.origin()==Origin.USER || e.origin()==Origin.TOOL)
            .count();
        assertEquals(0L, unprocessed, "all processed");
    }

    @Test
    public void testSimplePerceptionUsesGoalWhenEmpty() {
        DefaultExecutionContext ctx = new DefaultExecutionContext(
            Task.builder().instruction("test").build(),"t1","u1");
        ctx.goalStack().push(new Goal("root",null,GoalStatus.PENDING,
            "describe the weather",java.util.List.of(),Budget.unlimited()));
        SimplePerception p = new SimplePerception();
        Observations obs = p.perceive(ctx);
        assertEquals(1, obs.items().size(), "goal as observation");
        assertTrue(obs.items().get(0).content().contains("weather"), "goal content");
    }

    @Test
    public void testGroundingServiceIdentity() {
        GroundingService g = GroundingService.identity();
        Observation o = new Observation("test", Origin.USER, TrustTier.HIGH,
            Instant.now(), "src");
        Observation grounded = g.ground(o, null);
        assertEquals(o.content(), grounded.content(), "identity preserves content");
    }

    @Test
    public void testRelevanceFilterPassThrough() {
        RelevanceFilter f = RelevanceFilter.passThrough();
        java.util.List<Observation> obs = java.util.List.of(
            new Observation("a", Origin.USER, TrustTier.HIGH, Instant.now(), "src"),
            new Observation("b", Origin.TOOL, TrustTier.MEDIUM, Instant.now(), "src"));
        java.util.List<Observation> result = f.filter(obs, null);
        assertEquals(2, result.size(), "pass-through preserves all");
    }
}
