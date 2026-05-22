package com.agentframework.core;
import java.util.List; import java.util.Optional;
public interface GoalStack {
    void push(Goal goal);
    Optional<Goal> current();
    List<Goal> allActive();
    void updateStatus(String goalId, GoalStatus status);
    boolean isRootAchieved();
    void pop();
    int depth();
    List<Goal> all();
}
