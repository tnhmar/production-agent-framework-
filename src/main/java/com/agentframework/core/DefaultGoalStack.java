package com.agentframework.core;
import java.util.*;
import java.util.stream.Collectors;
public class DefaultGoalStack implements GoalStack {
    private final Deque<Goal>        stack    = new ArrayDeque<>();
    private final Map<String, Goal>  allGoals = new LinkedHashMap<>();

    public void push(Goal g) { allGoals.put(g.id(), g); stack.push(g); }

    public Optional<Goal> current() {
        return stack.isEmpty() ? Optional.empty() : Optional.of(stack.peek());
    }
    public List<Goal> allActive() {
        return allGoals.values().stream()
               .filter(g -> g.status()==GoalStatus.ACTIVE || g.status()==GoalStatus.PENDING)
               .collect(Collectors.toList());
    }
    public void updateStatus(String id, GoalStatus s) {
        Goal old = allGoals.get(id);
        if (old == null) return;
        Goal updated = old.withStatus(s);
        allGoals.put(id, updated);
        if (!stack.isEmpty() && stack.peek().id().equals(id)) {
            stack.pop(); stack.push(updated);
        }
    }
    public boolean isRootAchieved() {
        Goal r = allGoals.get("root");
        return r != null && r.status() == GoalStatus.COMPLETED;
    }
    public void pop()        { if (!stack.isEmpty()) stack.pop(); }
    public int  depth()      { return stack.size(); }
    public List<Goal> all()  { return new ArrayList<>(allGoals.values()); }
}
