package com.agentframework.tests;

import com.agentframework.action.DefaultAction;
import com.agentframework.action.DefaultToolDispatcher;
import com.agentframework.action.SafetyActionValidator;
import com.agentframework.action.SimpleToolRegistry;
import com.agentframework.action.middleware.ToolMiddleware;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.memory.impl.TieredMemory;
import com.agentframework.observability.AgentEvent;
import com.agentframework.observability.InMemoryEventSink;
import com.agentframework.perception.SimplePerception;
import com.agentframework.reasoning.LLMReasoning;
import com.agentframework.reasoning.PromptBuilder;
import com.agentframework.reasoning.StubLLMProvider;
import com.agentframework.reasoning.strategy.ReActStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test exercising streaming final answers through the full
 * framework stack using the shared StubLLMProvider and ReActStrategy.
 */
public class StreamingFinalAnswerTest {

    private AgentRuntime runtime(InMemoryEventSink sink) {
        return new AgentRuntime(new PassThroughPlanValidator(), sink);
    }

    private Agent streamingAgent(StubLLMProvider llm, SimpleToolRegistry reg) {
        DefaultToolDispatcher dispatcher = new DefaultToolDispatcher(reg);
        DefaultAction action = new DefaultAction(reg, List.of(new SafetyActionValidator()),
                ToolMiddleware.identity(), dispatcher);
        LLMReasoning reasoning = new LLMReasoning(llm, new ReActStrategy(),
                new PromptBuilder("You are a helpful agent.", reg, 4096));
        return Agent.builder()
                .name("streaming-agent")
                .perception(new SimplePerception())
                .reasoning(reasoning)
                .action(action)
                .memory(TieredMemory.Builder.inMemory())
                .build();
    }

    @Test
    void fullRunStreamsFinalAnswerTokens() {
        InMemoryEventSink sink = new InMemoryEventSink();
        SimpleToolRegistry registry = new SimpleToolRegistry();

        StubLLMProvider llm = StubLLMProvider.finalAnswer("Hello streaming world");
        Agent agent = streamingAgent(llm, registry);

        Task task = Task.builder()
                .instruction("Explain streaming")
                .maxCycles(5)
                .maxTokens(2048)
                .maxWallClockTime(Duration.ofSeconds(30))
                .build();

        List<String> tokens = new CopyOnWriteArrayList<>();
        List<FinalAnswer> answers = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        StreamListener listener = new StreamListener() {
            @Override
            public void onToken(String taskId, String token) {
                tokens.add(token);
            }

            @Override
            public void onComplete(String taskId, FinalAnswer answer) {
                answers.add(answer);
            }

            @Override
            public void onError(String taskId, Throwable cause) {
                errors.add(cause);
            }
        };

        AgentRuntime rt = runtime(sink);
        ExecutionResult result = rt.execute(agent, task, listener);

        assertTrue(result.succeeded(), "run should succeed");
        assertEquals("Hello streaming world", result.finalAnswer());

        assertFalse(tokens.isEmpty(), "Expected some streamed tokens");
        assertEquals(0, errors.size(), "No streaming errors expected");
        assertEquals(1, answers.size(), "Exactly one final answer expected");
        assertEquals("Hello streaming world", answers.getFirst().content());

        String streamed = String.join("", tokens);
        assertEquals("Hello streaming world", streamed);
        assertTrue(sink.count(AgentEvent.EventType.RUN_STARTED) >= 1);
        assertTrue(sink.count(AgentEvent.EventType.RUN_COMPLETED) >= 1);
    }
}
