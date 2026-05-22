package com.agentframework.tests;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.agentframework.action.*;
import com.agentframework.action.middleware.*;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.memory.impl.TieredMemory;
import com.agentframework.multi.*;
import com.agentframework.perception.SimplePerception;
import com.agentframework.reasoning.*;
import com.agentframework.reasoning.strategy.ReActStrategy;
import java.util.List;
public class MultiAgentTest {

    private Agent agentWith(LLMProvider llm) {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        return Agent.builder()
            .perception(new SimplePerception())
            .reasoning(new LLMReasoning(llm, new ReActStrategy(),
                new PromptBuilder("agent", reg, 2048)))
            .action(new DefaultAction(reg, List.of(), ToolMiddleware.identity(),
                new DefaultToolDispatcher(reg)))
            .memory(TieredMemory.Builder.inMemory())
            .build();
    }

    private AgentRuntime runtime() {
        return new AgentRuntime(new PassThroughPlanValidator());
    }

    private AgentHandle.Local localHandle(String name, String answer) {
        Agent a = agentWith(StubLLMProvider.finalAnswer(answer));
        AgentCard card = new AgentCard(name, "test agent", "1.0",
            List.of(new Skill("s1", name, "test skill", List.of("tag"))));
        return new AgentHandle.Local(runtime(), a, card);
    }

    @Test
    public void testAgentCard() {
        AgentCard card = new AgentCard("planner", "plans tasks", "1.0",
            List.of(new Skill("plan", "Planner", "plans tasks", List.of("planning", "research"))));
        assertTrue(card.hasSkill("plan"), "has plan skill");
        assertFalse(card.hasSkill("execute"), "no execute skill");
        assertTrue(card.hasTag("planning"), "has planning tag");
        assertFalse(card.hasTag("music"), "no music tag");
    }

    @Test
    public void testSupervisorOrchestratorSingleAgent() {
        Task task = Task.builder().instruction("say hello").maxCycles(5).build();
        AgentHandle.Local handle = localHandle("agent1", "hello world");
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");
        MultiAgentResult result = new SupervisorOrchestrator().coordinate(task, List.of(handle), ctx);
        assertNotNull(result.finalResult(), "has result");
        assertEquals(1, result.subTraces().size(), "1 trace");
    }

    @Test
    public void testSupervisorOrchestratorMultipleAgents() {
        Task task = Task.builder().instruction("work").maxCycles(5).build();
        List<AgentHandle> agents = List.of(
            localHandle("agent1", "step1"),
            localHandle("agent2", "step2"));
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");
        MultiAgentResult result = new SupervisorOrchestrator().coordinate(task, agents, ctx);
        assertEquals(2, result.subTraces().size(), "2 traces");
    }

    @Test
    public void testPipelineOrchestratorChains() {
        Task task = Task.builder().instruction("start").maxCycles(5).build();
        List<AgentHandle> agents = List.of(
            localHandle("stage1", "output-from-stage1"),
            localHandle("stage2", "final-output"));
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");
        MultiAgentResult result = new PipelineOrchestrator().coordinate(task, agents, ctx);
        assertNotNull(result.finalResult(), "pipeline result");
        assertEquals(2, result.subTraces().size(), "2 pipeline stages");
    }

    @Test
    public void testSupervisorEmptyAgentsThrows() {
        Task task = Task.builder().instruction("test").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");
        assertThrows(IllegalArgumentException.class,
            () -> new SupervisorOrchestrator().coordinate(task, List.of(), ctx),
            "empty agents throws");
    }

    @Test
    public void testAgentHandleSealed() {
        AgentHandle handle = localHandle("l", "result");
        String kind = switch (handle) {
            case AgentHandle.Local  loc -> "local";
            case AgentHandle.Remote rem -> "remote";
        };
        assertEquals("local", kind, "sealed AgentHandle");
    }

    @Test
    public void testCapabilitiesAndSecurity() {
        Capabilities caps = Capabilities.basic();
        SecurityScheme sec = SecurityScheme.none();
        assertFalse(caps.streaming(), "no streaming");
        assertTrue(caps.stateful(), "stateful");
        assertEquals("none", sec.type(), "no security scheme");
    }
}
