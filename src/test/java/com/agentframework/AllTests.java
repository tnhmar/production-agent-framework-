package com.agentframework;
import com.agentframework.testutil.TestRunner;
import com.agentframework.tests.*;
public class AllTests {
    public static void main(String[] args) {
        int failures = TestRunner.run(
            FoundationTest.class,
            CoreTest.class,
            ActionTest.class,
            MemoryTest.class,
            ReasoningTest.class,
            RuntimeTest.class,
            SecurityTest.class,
            HitlTest.class,
            MultiAgentTest.class,
            ObservabilityPerceptionTest.class
        );
        System.exit(failures > 0 ? 1 : 0);
    }
}
