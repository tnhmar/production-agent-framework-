package com.agentframework.multi;
import com.agentframework.core.*;
import java.util.List;
public sealed interface AgentHandle
    permits AgentHandle.Local, AgentHandle.Remote {
    record Local(AgentRuntime runtime, Agent agent, AgentCard card) implements AgentHandle {
        public Local(AgentRuntime runtime, Agent agent) {
            this(runtime, agent, new AgentCard("local","","",List.of()));
        }
    }
    record Remote(A2AClient client, AgentCard card) implements AgentHandle {}

    default AgentCard card() {
        return switch (this) {
            case Local  l -> l.card();
            case Remote r -> r.card();
        };
    }
    default boolean hasSkill(String skillId) { return card().hasSkill(skillId); }
}
