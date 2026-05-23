package com.agentframework.tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.agentframework.core.DefaultExecutionContext;
import com.agentframework.foundation.Task;
import com.agentframework.observability.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class ObservabilityExtendedTest {

    private DefaultExecutionContext ctx() {
        Task t = Task.builder().instruction("obs-test").build();
        return new DefaultExecutionContext(t, "t1", "u1");
    }

    @Test
    public void testInMemoryEventSinkEmitAndCount() {
        InMemoryEventSink sink = new InMemoryEventSink();
        sink.emit(new AgentEvent("r1","t1", AgentEvent.EventType.TOOL_CALLED,    Instant.now(), Map.of()));
        sink.emit(new AgentEvent("r1","t1", AgentEvent.EventType.TOOL_SUCCEEDED, Instant.now(), Map.of()));
        sink.emit(new AgentEvent("r1","t1", AgentEvent.EventType.TOOL_CALLED,    Instant.now(), Map.of()));

        assertEquals(3, sink.total(),                                         "total 3");
        assertEquals(2, sink.count(AgentEvent.EventType.TOOL_CALLED),         "2 TOOL_CALLED");
        assertEquals(1, sink.count(AgentEvent.EventType.TOOL_SUCCEEDED),      "1 TOOL_SUCCEEDED");
        assertEquals(0, sink.count(AgentEvent.EventType.TOOL_FAILED),         "0 TOOL_FAILED");
    }

    @Test
    public void testInMemoryEventSinkOf() {
        InMemoryEventSink sink = new InMemoryEventSink();
        sink.emit(new AgentEvent("r1","t1", AgentEvent.EventType.RUN_STARTED,   Instant.now(), Map.of("cycle","1")));
        sink.emit(new AgentEvent("r1","t1", AgentEvent.EventType.RUN_COMPLETED, Instant.now(), Map.of()));

        List<AgentEvent> started = sink.of(AgentEvent.EventType.RUN_STARTED);
        assertEquals(1, started.size());
        assertEquals("1", started.get(0).attributes().get("cycle"));
    }

    @Test
    public void testInMemoryEventSinkClear() {
        InMemoryEventSink sink = new InMemoryEventSink();
        sink.emit(new AgentEvent("r1","t1", AgentEvent.EventType.CYCLE_STARTED, Instant.now(), Map.of()));
        assertEquals(1, sink.total());
        sink.clear();
        assertEquals(0, sink.total(), "cleared");
    }

    @Test
    public void testInMemoryEventSinkAllReturnsCopy() {
        InMemoryEventSink sink = new InMemoryEventSink();
        sink.emit(new AgentEvent("r1","t1", AgentEvent.EventType.BELIEF_CONFLICT, Instant.now(), Map.of()));

        List<AgentEvent> snapshot = sink.all();
        assertEquals(1, snapshot.size());

        // Mutating the returned list must NOT affect the sink
        snapshot.clear();
        assertEquals(1, sink.total(), "sink unaffected by external mutation of all()");
    }

    @Test
    public void testRunMetricsSnapshot() {
        DefaultExecutionContext ctx = ctx();
        ctx.incrementCycle();
        ctx.incrementCycle();
        ctx.addTokens(500);

        RunMetrics m = new RunMetrics(ctx.runId());
        m.recordToolCall();
        m.recordToolCall();
        m.recordToolCall();
        m.recordToolFailure();

        MetricsSnapshot snap = m.snapshot(ctx);
        assertEquals(ctx.runId(), snap.runId(),       "runId");
        assertEquals(2,           snap.cycles(),      "cycles");
        assertEquals(500,         snap.totalTokens(), "tokens");
        assertEquals(3,           snap.toolCalls(),   "toolCalls");
        assertEquals(1,           snap.toolFailures(),"toolFailures");
        // MetricsSnapshot.durationMs() — not elapsedMs()
        assertTrue(snap.durationMs() >= 0, "duration non-negative");
    }

    @Test
    public void testRunMetricsNoFailures() {
        DefaultExecutionContext ctx = ctx();
        RunMetrics m = new RunMetrics(ctx.runId());
        m.recordToolCall();

        MetricsSnapshot snap = m.snapshot(ctx);
        assertEquals(1, snap.toolCalls(),    "1 call recorded");
        assertEquals(0, snap.toolFailures(), "0 failures");
    }

    @Test
    public void testAgentEventAllTypes() {
        for (AgentEvent.EventType type : AgentEvent.EventType.values()) {
            AgentEvent e = new AgentEvent("run", "tenant", type, Instant.now(), Map.of());
            assertNotNull(e.type(), "type must not be null for " + type);
            assertEquals(type, e.type());
        }
    }
}
