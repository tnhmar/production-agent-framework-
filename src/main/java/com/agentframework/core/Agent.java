package com.agentframework.core;
import com.agentframework.action.Action;
import com.agentframework.memory.Memory;
import com.agentframework.perception.Perception;
import com.agentframework.reasoning.Reasoning;
import java.util.Objects;
public final class Agent {
    private final String     name;
    private final Perception perception;
    private final Reasoning  reasoning;
    private final Action     action;
    private final Memory     memory;
    private Agent(Builder b) {
        name=b.name; perception=b.perception; reasoning=b.reasoning;
        action=b.action; memory=b.memory;
    }
    public String     name()       { return name; }
    public Perception perception() { return perception; }
    public Reasoning  reasoning()  { return reasoning; }
    public Action     action()     { return action; }
    public Memory     memory()     { return memory; }
    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private String name="agent"; private Perception perception;
        private Reasoning reasoning; private Action action; private Memory memory;
        public Builder name(String n)       { name=n;       return this; }
        public Builder perception(Perception p){ perception=p; return this; }
        public Builder reasoning(Reasoning r)  { reasoning=r;  return this; }
        public Builder action(Action a)       { action=a;     return this; }
        public Builder memory(Memory m)       { memory=m;     return this; }
        public Agent build() {
            Objects.requireNonNull(perception,"perception");
            Objects.requireNonNull(reasoning,"reasoning");
            Objects.requireNonNull(action,"action");
            Objects.requireNonNull(memory,"memory");
            return new Agent(this);
        }
    }
}
