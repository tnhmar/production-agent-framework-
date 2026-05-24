package com.agentframework.core;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Default goal stack implementation.
 *
 * <p><b>GS-1 fix:</b> all mutating and reading methods are now
 * {@code synchronized} on {@code this}, making the goal stack safe for
 * concurrent access from the HITL async resume path and any other async
 * orchestrator that touches the goal stack outside the main cycle thread.
 */
public class DefaultGoalStack implements GoalStack {
    private final Deque<Goal>       stack    = new ArrayDeque<>();
    private final Map<String, Goal> allGoals = new LinkedHashMap<>();

    public synchronized void push(Goal g) {
        allGoals.put(g.id(), g);
        stack.push(g);
    }

    public synchronized Optional<Goal> current() {
        return stack.isEmpty() ? Optional.empty() : Optional.of(stack.peek());
    }

    public synchronized List<Goal> allActive() {
        return allGoals.values().stream()
               .filter(g -> g.status() == GoalStatus.ACTIVE || g.status() == GoalStatus.PENDING)
               .collect(Collectors.toList());
    }

    public synchronized void updateStatus(String id, GoalStatus s) {
        Goal old = allGoals.get(id);
        if (old == null) return;
        Goal updated = old.withStatus(s);
        allGoals.put(id, updated);
        if (!stack.isEmpty() && stack.peek().id().equals(id)) {
            stack.pop();
            stack.push(updated);
        }
    }

    public synchronized boolean isRootAchieved() {
        Goal r = allGoals.get("root");
        return r != null && r.status() == GoalStatus.COMPLETED;
    }

    public synchronized void pop() {
        if (!stack.isEmpty()) stack.pop();
    }

    public synchronized int depth() { return stack.size(); }

    public synchronized List<Goal> all() { return new ArrayList<>(allGoals.values()); }
}
