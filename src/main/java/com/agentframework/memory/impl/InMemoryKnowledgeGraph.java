package com.agentframework.memory.impl;
import com.agentframework.core.RequestContext;
import com.agentframework.memory.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
public class InMemoryKnowledgeGraph implements KnowledgeGraph {
    private final CopyOnWriteArrayList<Triple> triples = new CopyOnWriteArrayList<>();

    public void upsert(Triple t, RequestContext ctx) { triples.add(t); }
    public void delete(Triple t, RequestContext ctx) { triples.remove(t); }
    public void deleteBySubject(String subject, RequestContext ctx) {
        triples.removeIf(t -> t.subject().equals(subject));
    }
    public List<Triple> query(String s, String p, String o, RequestContext ctx) {
        return triples.stream()
               .filter(t -> (s==null||t.subject().equals(s))
                         && (p==null||t.predicate().equals(p))
                         && (o==null||t.object().equals(o)))
               .collect(Collectors.toList());
    }
    public List<Triple> traverse(String start, List<String> predicates, int maxDepth, RequestContext ctx) {
        return triples.stream()
               .filter(t -> t.subject().equals(start) && predicates.contains(t.predicate()))
               .collect(Collectors.toList());
    }
    public int size() { return triples.size(); }
}
