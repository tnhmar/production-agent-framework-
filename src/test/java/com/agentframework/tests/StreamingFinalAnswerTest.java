package com.agentframework.tests;

import com.agentframework.action.*;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.memory.*;
import com.agentframework.observability.*;
import com.agentframework.perception.*;
import com.agentframework.reasoning.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test exercising streaming final answers through the full
 * framework stack using a StubLLMProvider.
 */
public class StreamingFinalAnswerTest {

    /** Simple stub LLM that ignores the prompt and returns a fixed answer. */
    static final class StubLLMProvider implements LLMProvider {
        @Override
        public String generate(Prompt prompt) {
            return "This should not be used in streaming test";
        }

        @Override
        public Stream<String> generateStream(Prompt prompt) {
            // Stream a short sentence token-by-token to keep ordering obvious
            String text = "Hello streaming world";
            List<String> tokens = new ArrayList<>();
            for (char c : text.toCharArray()) {
                tokens.add(String.valueOf(c));
            }
            return tokens.stream();
        }

        @Override
        public String name() { return "stub-stream"; }
    }

    @Test
    void fullRunStreamsFinalAnswerTokens() {
        // Perception: no external observations
        Perception perception = (ctx) -> Observations.empty();

        // Reasoning: trivial strategy that always returns a FinalAnswer
        ReasoningStrategy strategy = new ReasoningStrategy() {
            @Override
            public String outputSchemaDescription() {
                return ""; // not used in this test
            }

            @Override
            public Decision parse(String llmOutput) {
                return new FinalAnswer(llmOutput, List.of());
            }
        };

        ToolRegistry tools = ToolRegistry.empty();
        PromptBuilder promptBuilder = new PromptBuilder(
                "You are a helpful agent.", tools, 512);
        LLMReasoning reasoning = new LLMReasoning(new StubLLMProvider(), strategy, promptBuilder);

        Action action = new DefaultAction(tools);
        Memory memory = new TieredMemory();
        Agent agent = Agent.builder()
                .name("streaming-agent")
                .perception(perception)
                .reasoning(reasoning)
                .action(action)
                .memory(memory)
                .build();

        Task task = new Task(
                "test-streaming", "tenant", "user",
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

        PlanValidator validator = new PlanValidator();
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
