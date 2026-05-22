package com.agentframework.foundation;
import java.util.List;
public record Observations(List<Observation> items) {
    public static Observations of(List<Observation> items){ return new Observations(List.copyOf(items)); }
    public static Observations empty()                    { return new Observations(List.of()); }
    public boolean isEmpty()                              { return items.isEmpty(); }
}
