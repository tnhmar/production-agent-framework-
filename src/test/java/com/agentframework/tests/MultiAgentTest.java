package com.agentframework.tests;
import com.agentframework.action.*;
import com.agentframework.action.middleware.*;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.memory.impl.TieredMemory;
import com.agentframework.multi.*;
import com.agentframework.perception.SimplePerception;
import com.agentframework.reasoning.*;
import com.agentframework.reasoning.strategy.ReActStrategy;
import com.agentframework.testutil.Assert;
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

    public void testAgentCard() {
        AgentCard card = new AgentCard("planner", "plans tasks", "1.0",
            List.of(new Skill("plan", "Planner", "plans tasks", List.of("planning", "research"))));
        Assert.assertTrue(card.hasSkill("plan"), "has plan skill");
        Assert.assertFalse(card.hasSkill("execute"), "no execute skill");
        Assert.assertTrue(card.hasTag("planning"), "has planning tag");
        Assert.assertFalse(card.hasTag("music"), "no music tag");
    }

    public void testSupervisorOrchestratorSingleAgent() {
        Task task = Task.builder().instruction("say hello").maxCycles(5).build();
        AgentHandle.Local handle = localHandle("agent1", "hello world");
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");
        MultiAgentResult result = new SupervisorOrchestrator().coordinate(task, List.of(handle), ctx);
        Assert.assertNotNull(result.finalResult(), "has result");
        Assert.assertEquals(1, result.subTraces().size(), "1 trace");
    }

    public void testSupervisorOrchestratorMultipleAgents() {
        Task task = Task.builder().instruction("work").maxCycles(5).build();
        List<AgentHandle> agents = List.of(
            localHandle("agent1", "step1"),
            localHandle("agent2", "step2"));
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");
        MultiAgentResult result = new SupervisorOrchestrator().coordinate(task, agents, ctx);
        Assert.assertEquals(2, result.subTraces().size(), "2 traces");
    }

    public void testPipelineOrchestratorChains() {
        Task task = Task.builder().instruction("start").maxCycles(5).build();
        List<AgentHandle> agents = List.of(
            localHandle("stage1", "output-from-stage1"),
            localHandle("stage2", "final-output"));
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");
        MultiAgentResult result = new PipelineOrchestrator().coordinate(task, agents, ctx);
        Assert.assertNotNull(result.finalResult(), "pipeline result");
        Assert.assertEquals(2, result.subTraces().size(), "2 pipeline stages");
    }

    public void testSupervisorEmptyAgentsThrows() {
        Task task = Task.builder().instruction("test").build();
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "t1", "u1");
        Assert.assertThrows(IllegalArgumentException.class,
            () -> new SupervisorOrchestrator().coordinate(task, List.of(), ctx),
            "empty agents throws");
    }

    public void testAgentHandleSealed() {
        AgentHandle handle = localHandle("l", "result");
        String kind = switch (handle) {
            case AgentHandle.Local  loc -> "local";
            case AgentHandle.Remote rem -> "remote";
        };
        Assert.assertEquals("local", kind, "sealed AgentHandle");
    }

    public void testCapabilitiesAndSecurity() {
        Capabilities caps = Capabilities.basic();
        SecurityScheme sec = SecurityScheme.none();
        Assert.assertFalse(caps.streaming(), "no streaming");
        Assert.assertTrue(caps.stateful(), "stateful");
        Assert.assertEquals("none", sec.type(), "no security scheme");
    }
}
