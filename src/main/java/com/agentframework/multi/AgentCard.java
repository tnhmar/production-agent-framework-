package com.agentframework.multi;
import java.util.List;
public record AgentCard(String name, String description, String version,
        List<Skill> skills, Capabilities capabilities, SecurityScheme security) {
    public AgentCard(String name, String description, String version, List<Skill> skills) {
        this(name, description, version, skills, Capabilities.basic(), SecurityScheme.none());
    }
    public boolean hasSkill(String skillId) {
        return skills.stream().anyMatch(s -> s.id().equals(skillId));
    }
    public boolean hasTag(String tag) {
        return skills.stream().flatMap(s -> s.tags().stream()).anyMatch(t -> t.equals(tag));
    }
}
