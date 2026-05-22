package com.agentframework.core;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
public class DefaultBeliefState implements BeliefState {
    // Key: subject + "|" + predicate
    private final ConcurrentHashMap<String, Belief>       beliefs     = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<BeliefConflict>    conflictLog = new CopyOnWriteArrayList<>();

    public Belief assertBelief(Belief incoming) {
        Objects.requireNonNull(incoming, "incoming belief must not be null");
        String key = incoming.subject() + "|" + incoming.predicate();
        return beliefs.compute(key, (k, existing) -> {
            if (existing == null) return incoming;
            if (!Objects.equals(existing.object(), incoming.object())) {
                conflictLog.add(new BeliefConflict(
                    existing.beliefId(), incoming.subject(), incoming.predicate(),
                    existing.object(), incoming.object(),
                    existing.confidence(), incoming.confidence(),
                    incoming.provenance(), Instant.now()));
                return incoming.confidence() >= existing.confidence()
                    ? incoming.withConflicted(true)
                    : existing.withConflicted(true);
            }
            return incoming.confidence() > existing.confidence() ? incoming : existing;
        });
    }

    public void retract(String id) {
        beliefs.values().removeIf(b -> b.beliefId().equals(id));
    }

    public Optional<Belief> getBySPO(String subject, String predicate) {
        return Optional.ofNullable(beliefs.get(subject + "|" + predicate));
    }

    public List<Belief> all(double minConf) {
        return beliefs.values().stream()
               .filter(b -> b.confidence() >= minConf)
               .collect(Collectors.toList());
    }

    public List<BeliefConflict> conflicts() { return new ArrayList<>(conflictLog); }

    public void resolveConflict(String subject, String predicate, String winningBeliefId) {
        String key = subject + "|" + predicate;
        Belief b = beliefs.get(key);
        if (b != null && b.beliefId().equals(winningBeliefId))
            beliefs.put(key, b.withConflicted(false));
    }

    public int size() { return beliefs.size(); }
}
