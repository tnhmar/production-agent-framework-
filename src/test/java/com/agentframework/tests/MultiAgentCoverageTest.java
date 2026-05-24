package com.agentframework.tests;

import com.agentframework.core.*;
import com.agentframework.foundation.Task;
import com.agentframework.multi.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for PipelineOrchestrator Remote branch,
 * AgentCard, Skill, SupervisorOrchestrator.
 * All API calls verified against actual source before push.
 */
public class MultiAgentCoverageTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a minimal AgentCard using its canonical record constructor:
     * AgentCard(String name, String description, String version, String url,
     *           List<Skill> skills, Capabilities capabilities, SecurityScheme security)
     */
    private static AgentCard card(String name, String url) {
        return new AgentCard(
            name, "test agent", "1.0", url,
            List.of(new Skill("op", "op", "runs op", List.of("test"))),
            Capabilities.basic(),
            SecurityScheme.none());
    }

    /**
     * A2AClient is NOT a functional interface (has sendTask + checkStatus).
     * Must use anonymous class.
     */
    private static A2AClient stubbedClient(String taskId, String state, Object result) {
        return new A2AClient() {
            public A2ATask sendTask(TaskSpec spec) {
                return new A2ATask(taskId, state, result, null);
            }
            public A2ATask checkStatus(String id) {
                return new A2ATask(id, state, result, null);
            }
        };
    }

    private static ExecutionContext minimalCtx(Task task) {
        return new DefaultExecutionContext(task, "tenant-1", "run-1");
    }

    // ── PipelineOrchestrator: empty agents → IAE ──────────────────────────────

    @Test
    void testPipelineOrchestratorEmptyAgentsThrowsIAE() {
        PipelineOrchestrator pipeline = new PipelineOrchestrator();
        Task task = Task.builder().instruction("do something").build();
        assertThrows(IllegalArgumentException.class,
            () -> pipeline.coordinate(task, List.of(), minimalCtx(task)),
            "empty agents list must throw IAE");
    }

    // ── PipelineOrchestrator: Remote handle — success path ────────────────────

    @Test
    void testPipelineOrchestratorRemoteAgentSuccess() {
        // AgentHandle.Remote(A2AClient client, AgentCard card) — client is FIRST
        AgentHandle remote = new AgentHandle.Remote(
            stubbedClient("task-001", "completed", "done by remote"),
            card("remote-agent", "http://localhost:9999"));

        PipelineOrchestrator pipeline = new PipelineOrchestrator();
        Task task = Task.builder().instruction("process this input").build();
        ExecutionContext ctx = minimalCtx(task);

        MultiAgentResult result = pipeline.coordinate(task, List.of(remote), ctx);

        // MultiAgentResult fields: finalResult(), contributors(), correlationId(), subTraces()
        assertNotNull(result, "result must not be null");
        assertNotNull(result.finalResult(), "finalResult must not be null");
        assertTrue(result.finalResult().toString().contains("done by remote"),
            "remote agent output forwarded");
        assertEquals(1, result.subTraces().size(), "one trace entry");
        assertEquals("completed", result.subTraces().get(0).state(), "state completed");
    }

    // ── PipelineOrchestrator: Remote handle — null result path ────────────────

    @Test
    void testPipelineOrchestratorRemoteNullResult() {
        AgentHandle remote = new AgentHandle.Remote(
            stubbedClient("task-002", "failed", null),
            card("failing-agent", "http://localhost:9998"));

        PipelineOrchestrator pipeline = new PipelineOrchestrator();
        Task task = Task.builder().instruction("fail me").build();

        // Must not throw even when remote returns null result
        assertDoesNotThrow(() ->
            pipeline.coordinate(task, List.of(remote), minimalCtx(task)));
    }

    // ── AgentCard record accessors ────────────────────────────────────────────

    @Test
    void testAgentCardAccessors() {
        // Skill(String id, String name, String description, List<String> tags)
        Skill skill = new Skill("translate", "Translator", "Translates text",
            List.of("nlp", "translation"));
        AgentCard c = new AgentCard(
            "translator", "NLP agent", "2.1",
            "https://agents.example.com/translate",
            List.of(skill),
            Capabilities.basic(),
            SecurityScheme.none());

        assertEquals("translator",                         c.name());
        assertEquals("https://agents.example.com/translate", c.url());
        assertEquals("2.1",                                c.version());
        assertEquals(1,                                    c.skills().size());
        assertEquals("translate",                          c.skills().get(0).id());
        assertTrue(c.isRemoteCapable(),   "has url → isRemoteCapable");
        assertTrue(c.hasSkill("translate"), "hasSkill translate");
        assertTrue(c.hasTag("nlp"),         "hasTag nlp");
    }

    @Test
    void testAgentCardWithoutUrlIsNotRemoteCapable() {
        // Backward-compat constructor: (name, description, version, List<Skill>)
        AgentCard local = new AgentCard("local-agent", "local", "1.0",
            List.of());
        assertFalse(local.isRemoteCapable(), "no URL → not remote capable");
    }

    // ── Skill record ──────────────────────────────────────────────────────────

    @Test
    void testSkillRecordFields() {
        Skill s = new Skill("summarize", "Summarizer", "Summarizes documents",
            List.of("nlp", "summary"));
        assertEquals("summarize",  s.id());
        assertEquals("Summarizer", s.name());
        assertEquals("Summarizes documents", s.description());
        assertEquals(List.of("nlp", "summary"), s.tags());
    }

    // ── SupervisorOrchestrator: empty agents → IAE ────────────────────────────

    @Test
    void testSupervisorOrchestratorEmptyAgentsThrowsIAE() {
        // SupervisorOrchestrator also throws IAE on empty agents
        SupervisorOrchestrator supervisor = new SupervisorOrchestrator();
        Task task = Task.builder().instruction("do X").build();
        assertThrows(IllegalArgumentException.class,
            () -> supervisor.coordinate(task, List.of(), minimalCtx(task)),
            "supervisor with empty agents must throw IAE");
    }

    // ── SupervisorOrchestrator: Remote handle path ────────────────────────────

    @Test
    void testSupervisorOrchestratorRemoteAgent() {
        AgentHandle remote = new AgentHandle.Remote(
            stubbedClient("sup-001", "completed", "supervisor output"),
            card("sup-agent", "http://localhost:9997"));

        SupervisorOrchestrator supervisor = new SupervisorOrchestrator();
        Task task = Task.builder().instruction("supervise this").build();
        MultiAgentResult result = supervisor.coordinate(
            task, List.of(remote), minimalCtx(task));

        assertNotNull(result);
        assertNotNull(result.finalResult());
        assertEquals(1, result.subTraces().size());
    }

    // ── Capabilities record ───────────────────────────────────────────────────

    @Test
    void testCapabilitiesBasic() {
        Capabilities c = Capabilities.basic();
        // basic() = (streaming=false, pushNotifications=false, stateful=true)
        assertFalse(c.streaming());
        assertFalse(c.pushNotifications());
        assertTrue(c.stateful());
    }

    @Test
    void testCapabilitiesCustom() {
        Capabilities c = new Capabilities(true, true, false);
        assertTrue(c.streaming());
        assertTrue(c.pushNotifications());
        assertFalse(c.stateful());
    }

    // ── SecurityScheme record ─────────────────────────────────────────────────

    @Test
    void testSecuritySchemeNone() {
        SecurityScheme s = SecurityScheme.none();
        assertEquals("none", s.type());
    }

    @Test
    void testSecuritySchemeCustom() {
        SecurityScheme s = new SecurityScheme("bearer", "https://iss", "https://iss/jwks");
        assertEquals("bearer",        s.type());
        assertEquals("https://iss",   s.issuer());
        assertEquals("https://iss/jwks", s.jwksUrl());
    }
}
