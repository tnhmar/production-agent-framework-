package com.agentframework.testutil;
import java.lang.reflect.*;
import java.util.*;
public final class TestRunner {
    public static int run(Class<?>... suites) {
        int passed=0, failed=0;
        for (Class<?> suite : suites) {
            System.out.println("\n=== " + suite.getSimpleName() + " ===");
            for (Method m : suite.getDeclaredMethods()) {
                if (!m.getName().startsWith("test")) continue;
                try {
                    m.invoke(suite.getDeclaredConstructor().newInstance());
                    System.out.println("  PASS  " + m.getName());
                    passed++;
                } catch (InvocationTargetException ite) {
                    System.out.println("  FAIL  " + m.getName() + " -> " + ite.getCause().getMessage());
                    ite.getCause().printStackTrace(System.out);
                    failed++;
                } catch (Exception e) {
                    System.out.println("  ERROR " + m.getName() + " -> " + e.getMessage());
                    failed++;
                }
            }
        }
        System.out.printf("%n--- TOTAL: %d passed, %d failed ---%n", passed, failed);
        return failed;
    }
}
