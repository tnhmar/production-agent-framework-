package com.agentframework.foundation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A named, versioned, owned procedure record.
 *
 * <p>Vol.1 Ch.10 (secprocedural) classifies procedural memory as <em>what the agent can do</em>:
 * tool-usage patterns, workflow templates, action heuristics, and learned strategies distilled
 * from successful past executions. A {@code WorkflowTemplate} is the canonical value type for
 * the third of those four items.
 *
 * <p><strong>Storage tier (Vol.1 taxonomy table, tabpart3-map):</strong>
 * system prompt · tool library · external store — NOT the vector DB used by episodic/semantic.
 *
 * <p><strong>Access pattern:</strong> direct injection or retrieval — never similarity search.
 *
 * <p><strong>Pattern 4 exclusion note:</strong> Fine-tuning (baking procedures into model weights)
 * is the fourth Vol.1 implementation pattern for procedural memory. That concern belongs to the
 * {@code LLMProvider} abstraction, not to this store. No implementation of {@link ProceduralStore}
 * should attempt to represent fine-tuned behaviour as a retrievable template.
 */
public final class WorkflowTemplate {

    /** Stable identifier used as the lookup key across sessions. */
    private final String templateId;

    /**
     * Human-readable name for the task class this template covers.
     * Example: "deploy-pipeline", "fraud-investigation", "onboard-user".
     */
    private final String taskType;

    /**
     * Ordered list of step descriptors. Each step is a plain-language instruction
     * that will be injected into the agent's system prompt section — not into the
     * episodic/semantic context block.
     */
    private final List<String> steps;

    /**
     * Supplementary action heuristics that apply across all steps.
     * Example: "If a query returns more than 1 000 rows, add a LIMIT clause before executing."
     */
    private final List<String> heuristics;

    /**
     * Semantic version string (major.minor.patch). Version your system prompt as source code
     * — Vol.1 takeaway box, secprocedural.
     */
    private final String version;

    /**
     * Team or individual responsible for maintaining this template.
     * Required by Vol.1: "ownership and change history" mandate.
     */
    private final String owner;

    /** Wall-clock time at which this version was registered. Used for audit records. */
    private final Instant registeredAt;

    public WorkflowTemplate(
            String templateId,
            String taskType,
            List<String> steps,
            List<String> heuristics,
            String version,
            String owner,
            Instant registeredAt) {
        this.templateId   = Objects.requireNonNull(templateId,   "templateId");
        this.taskType     = Objects.requireNonNull(taskType,     "taskType");
        this.steps        = List.copyOf(Objects.requireNonNull(steps, "steps"));
        this.heuristics   = List.copyOf(Objects.requireNonNull(heuristics, "heuristics"));
        this.version      = Objects.requireNonNull(version,      "version");
        this.owner        = Objects.requireNonNull(owner,        "owner");
        this.registeredAt = Objects.requireNonNull(registeredAt, "registeredAt");
    }

    /** Convenience factory — sets {@code registeredAt} to now. */
    public static WorkflowTemplate of(
            String templateId,
            String taskType,
            List<String> steps,
            List<String> heuristics,
            String version,
            String owner) {
        return new WorkflowTemplate(
                templateId, taskType, steps, heuristics, version, owner, Instant.now());
    }

    /**
     * Renders this template as a compact system-prompt injection block.
     *
     * <p>The returned string is intended for insertion into the <em>system prompt section</em>
     * of the context window — not into the episodic/semantic facts block. This matches the
     * Vol.1 Pattern 1 access pattern: direct injection as instructions, not facts to reason about.
     */
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Workflow: ").append(taskType)
          .append(" (v").append(version).append(")\n");
        for (int i = 0; i < steps.size(); i++) {
            sb.append(i + 1).append(". ").append(steps.get(i)).append("\n");
        }
        if (!heuristics.isEmpty()) {
            sb.append("\nHeuristics:\n");
            heuristics.forEach(h -> sb.append("- ").append(h).append("\n"));
        }
        return sb.toString();
    }

    public String getTemplateId()   { return templateId; }
    public String getTaskType()     { return taskType; }
    public List<String> getSteps()  { return steps; }
    public List<String> getHeuristics() { return heuristics; }
    public String getVersion()      { return version; }
    public String getOwner()        { return owner; }
    public Instant getRegisteredAt(){ return registeredAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkflowTemplate)) return false;
        WorkflowTemplate that = (WorkflowTemplate) o;
        return templateId.equals(that.templateId) && version.equals(that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(templateId, version);
    }

    @Override
    public String toString() {
        return "WorkflowTemplate{id='" + templateId + "', taskType='" + taskType +
               "', version='" + version + "', owner='" + owner + "', steps=" + steps.size() + "}";
    }
}
