package com.agentframework.tests;

import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.multi.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for PipelineOrchestrator Remote branch,
 * AgentCard, SupervisorOrchestrator edge cases.
 */
public class MultiAgentCoverageTest {

    // ── PipelineOrchestrator: empty agents → exception ────────────────────────

    @Test
    void testPipelineOrchestratorEmptyAgentsThrows() {
        PipelineOrchestrator pipeline = new PipelineOrchestrator();
        Task task = Task.builder().instruction("do something").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");
        assertThrows(IllegalArgumentException.class,
            () -> pipeline.coordinate(task, List.of(), ctx),
            "empty agents list must throw IAE");
    }

    // ── PipelineOrchestrator: Remote handle branch ───────────────────────────

    @Test
    void testPipelineOrchestratorRemoteAgent() {
        A2AClient stubClient = spec -> new A2ATask(
            "task-001",
            "COMPLETED",
            spec.instruction() + " [done by remote]");

        AgentCard card = AgentCard.builder()
            .name("remote-agent")
            .url("http://localhost:9999")
            .capabilities(Capabilities.of(List.of(
                new Skill("summarize", "Summarizes text"))))
            .securityScheme(SecurityScheme.none())
            .version("1.0")
            .build();

        AgentHandle remote = new AgentHandle.Remote(card, stubClient);

        PipelineOrchestrator pipeline = new PipelineOrchestrator();
        Task task = Task.builder().instruction("process this input").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");

        MultiAgentResult result = pipeline.coordinate(task, List.of(remote), ctx);

        assertNotNull(result, "result must not be null");
        assertNotNull(result.output(), "output must not be null");
        assertTrue(result.output().toString().contains("done by remote"),
            "remote agent output forwarded");
        assertEquals(1, result.traces().size(), "one trace entry");
        assertEquals("COMPLETED", result.traces().get(0).state(), "state COMPLETED");
    }

    // ── PipelineOrchestrator: Remote returns null result ─────────────────────

    @Test
    void testPipelineOrchestratorRemoteNullResult() {
        A2AClient stubNull = spec -> new A2ATask("task-002", "FAILED", null);

        AgentCard card = AgentCard.builder()
            .name("failing-agent")
            .url("http://localhost:9998")
            .capabilities(Capabilities.of(List.of()))
            .securityScheme(SecurityScheme.none())
            .version("1.0")
            .build();

        AgentHandle remote = new AgentHandle.Remote(card, stubNull);
        PipelineOrchestrator pipeline = new PipelineOrchestrator();
        Task task = Task.builder().instruction("fail me").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");

        assertDoesNotThrow(() -> pipeline.coordinate(task, List.of(remote), ctx));
    }

    // ── AgentCard builder + accessors ─────────────────────────────────────────

    @Test
    void testAgentCardBuilderAndAccessors() {
        Skill s = new Skill("translate", "Translates text");
        AgentCard card = AgentCard.builder()
            .name("translator")
            .url("https://agents.example.com/translate")
            .capabilities(Capabilities.of(List.of(s)))
            .securityScheme(SecurityScheme.bearerToken())
            .version("2.1")
            .build();

        assertEquals("translator", card.name());
        assertEquals("https://agents.example.com/translate", card.url());
        assertEquals("2.1", card.version());
        assertFalse(card.capabilities().skills().isEmpty());
        assertEquals("translate", card.capabilities().skills().get(0).name());
    }

    @Test
    void testAgentCardNameUrlConsistency() {
        AgentCard c1 = AgentCard.builder()
            .name("a").url("http://x")
            .capabilities(Capabilities.of(List.of()))
            .securityScheme(SecurityScheme.none()).version("1").build();
        AgentCard c2 = AgentCard.builder()
            .name("a").url("http://x")
            .capabilities(Capabilities.of(List.of()))
            .securityScheme(SecurityScheme.none()).version("1").build();
        assertEquals(c1.name(), c2.name(), "same name");
        assertEquals(c1.url(),  c2.url(),  "same url");
    }

    // ── SupervisorOrchestrator: no-agents path ────────────────────────────────

    @Test
    void testSupervisorWithNoCapableAgents() {
        SupervisorOrchestrator supervisor = new SupervisorOrchestrator();
        Task task = Task.builder().instruction("do X").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");
        MultiAgentResult result = supervisor.coordinate(task, List.of(), ctx);
        assertNotNull(result, "result must not be null even with no agents");
    }
}
