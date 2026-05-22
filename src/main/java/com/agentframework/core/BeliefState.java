package com.agentframework.core;
import java.util.List;
import java.util.Optional;
public interface BeliefState {
    /** Assert or update a belief; returns the belief that won (highest confidence). */
    Belief assertBelief(Belief incoming);
    void   retract(String beliefId);
    Optional<Belief> getBySPO(String subject, String predicate);
    List<Belief>     all(double minConfidence);
    List<BeliefConflict> conflicts();
    void resolveConflict(String subject, String predicate, String winningBeliefId);
    int size();
}
