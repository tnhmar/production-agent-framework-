package com.agentframework.multi;

import java.util.List;

/**
 * Describes an agent's identity, skills, and capabilities.
 *
 * <p>m1 fix: added {@code url} field — required by the A2A protocol specification
 * for the agent card published at {@code .well-known/agent.json}. The URL is the
 * base endpoint at which the agent accepts A2A task submissions.
 */
public record AgentCard(
        String       name,
        String       description,
        String       version,
        String       url,           // m1 fix: A2A endpoint URL
        List<Skill>  skills,
        Capabilities capabilities,
        SecurityScheme security) {

    /** Full constructor with all fields. */
    public AgentCard {
        skills = skills != null ? List.copyOf(skills) : List.of();
    }

    /** Convenience: name, description, version, URL and skills — no security. */
    public AgentCard(String name, String description, String version, String url, List<Skill> skills) {
        this(name, description, version, url, skills, Capabilities.basic(), SecurityScheme.none());
    }

    /** Backward-compatible constructor without URL (local-only agents). */
    public AgentCard(String name, String description, String version, List<Skill> skills) {
        this(name, description, version, null, skills, Capabilities.basic(), SecurityScheme.none());
    }

    public boolean hasSkill(String skillId) {
        return skills.stream().anyMatch(s -> s.id().equals(skillId));
    }

    public boolean hasTag(String tag) {
        return skills.stream().flatMap(s -> s.tags().stream()).anyMatch(t -> t.equals(tag));
    }

    /** Returns true if this card has an A2A-reachable endpoint URL. */
    public boolean isRemoteCapable() {
        return url != null && !url.isBlank();
    }
}
