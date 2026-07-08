package com.agentframework.tests;

import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.memory.WorkingMemory;
import com.agentframework.observability.*;
import com.agentframework.perception.Perception;
import com.agentframework.reasoning.LLMReasoning;
import com.agentframework.reasoning.StubLLMProvider;
import com.agentframework.reasoning.strategy.ReActStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test exercising streaming final answers through the full
 * framework stack using the shared StubLLMProvider and ReActStrategy.
 */
public class StreamingFinalAnswerTest {

    @Test
    void fullRunStreamsFinalAnswerTokens() {
        // Perception: no external observations
        Perception perception = ctx -> Observations.empty();

        // Use the shared StubLLMProvider with a simple script: one final answer
        StubLLMProvider llm = new StubLLMProvider()
                .then(StubLLMProvider.finalAnswerJson("Hello streaming world"));

        ReActStrategy strategy = new ReActStrategy();

        // LLMReasoning wired the same way as in ReasoningIntegrationTest, but
        // we care about the streaming API rather than run()
        LLMReasoning reasoning = new LLMReasoning(llm, strategy, com.agentframework.action.ToolRegistry.empty());

        Agent agent = Agent.builder()
                .name("streaming-agent")
                .perception(perception)
                .reasoning(reasoning)
                .action(new com.agentframework.action.NoopAction())
                .memory(WorkingMemory.create())
                .build();

        Task task = new Task(
                "Explain streaming", 10, 2048,
                Duration.ofMinutes(1), null, 3);
        DefaultExecutionContext ctx = new DefaultExecutionContext(task, "tenant", "user");

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

        PlanValidator validator = new PlanValidator() {
            @Override
            public ValidationResult validate(Decision decision, ExecutionContext ctx) {
                return ValidationResult.passed();
            }
        };
        EventSink events = new NoopEventSink();
        StateMachineRunner runner = new StateMachineRunner(validator, events);
        runner.setStreamListener(listener);

        runner.run(agent, ctx);

        assertTrue(tokens.size() > 0, "Expected some streamed tokens");
        assertEquals(0, errors.size(), "No streaming errors expected");
        assertEquals(1, answers.size(), "Exactly one final answer expected");

        String streamed = String.join("", tokens);
        assertEquals("Hello streaming world", streamed);
    }
}
