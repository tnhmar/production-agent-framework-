package com.agentframework.foundation;

import java.util.List;
import java.util.Optional;

/**
 * Contract for the procedural memory subsystem.
 *
 * <p><strong>Vol.1 Ch.10 alignment (secprocedural + secmemory-subsystem):</strong>
 * <ul>
 *   <li>Procedural memory encodes <em>what to do</em>, not what to know. It is structurally
 *       separated from the episodic/semantic retrieve-and-inject pipeline.</li>
 *   <li>This interface is the single gate through which all framework components interact with
 *       procedural memory. No component reaches directly into a store implementation.</li>
 *   <li>The {@code memory} module is intentionally scoped to semantic and episodic records only.
 *       This interface lives in {@code foundation} so that {@code core}, {@code reasoning}, and
 *       any future {@code rag}-backed implementation can all depend on it without circular
 *       module dependencies.</li>
 * </ul>
 *
 * <p><strong>Implementation tiers (Vol.1 Patterns 1–3):</strong>
 * <ol>
 *   <li><em>Pattern 1 — System prompt injection:</em> stable, universal procedures encoded
 *       directly in {@code Task.systemPrompt}. No store interaction at runtime.</li>
 *   <li><em>Pattern 2 — Tool library:</em> {@code ToolRegistry} (action module) is a form of
 *       distributed procedural memory. No separate store needed.</li>
 *   <li><em>Pattern 3 — External retrieval store (this interface):</em> task-specific or
 *       per-user workflow templates retrieved at runtime. The default implementation is
 *       {@code InMemoryProceduralStore} (core module). A future {@code VectorProceduralStore}
 *       in the {@code rag} module is the OCP-clean extension point for production deployments
 *       requiring similarity-based retrieval.</li>
 * </ol>
 *
 * <p><strong>Pattern 4 exclusion:</strong> Fine-tuning (Vol.1 Pattern 4) bakes procedures into
 * model weights and is handled at the {@code LLMProvider} abstraction layer. No implementation
 * of this interface should attempt to represent fine-tuned behaviour as a retrievable template.
 *
 * <p><strong>Audit contract:</strong> Every call to {@link #register(WorkflowTemplate)} MUST
 * produce a write-immutable audit record (consistent with the Vol.1 Ch.10 lifecycle Stage 1
 * write-gate mandate, which applies to all memory record types including procedural). The audit
 * record must capture: templateId, version, owner, registeredAt, and the calling agentId if
 * available.
 *
 * <p><strong>Write-gate contract:</strong> Before persisting, implementations MUST check:
 * <ol>
 *   <li>Significance — is this template likely to be retrieved?</li>
 *   <li>Duplication — does a semantically equivalent template already exist for this taskType
 *       and version? If so, reject or replace explicitly.</li>
 *   <li>Sensitivity — does the template encode PII-handling rules or credentials that must
 *       not be persisted in plain text?</li>
 * </ol>
 */
public interface ProceduralStore {

    /**
     * Registers a new workflow template, applying the write-gate checks and emitting an
     * audit record. If a template with the same {@code templateId} and {@code version}
     * already exists, the behaviour is implementation-defined (replace or reject).
     *
     * @param template the template to register; must not be null
     */
    void register(WorkflowTemplate template);

    /**
     * Retrieves the best-matching template for the given task type.
     *
     * <p>In {@code InMemoryProceduralStore} this is an exact match on {@code taskType}.
     * In a future {@code VectorProceduralStore} this may be similarity-based retrieval.
     * Callers must not assume either behaviour — depend only on this contract.
     *
     * @param taskType the task class identifier; must not be null
     * @return the best-matching template, or empty if none registered for this task type
     */
    Optional<WorkflowTemplate> retrieve(String taskType);

    /**
     * Looks up a template by its stable identifier and version.
     *
     * @param templateId the template identifier
     * @param version    the semantic version string
     * @return the template if found, or empty
     */
    Optional<WorkflowTemplate> findById(String templateId, String version);

    /**
     * Returns all registered templates, ordered by registration time (oldest first).
     * Intended for admin tooling and audit inspection — not for agent reasoning paths.
     */
    List<WorkflowTemplate> listAll();

    /**
     * Removes the template with the given id and version from the store.
     * Implementations must emit an audit record for the deletion.
     *
     * @param templateId the template identifier
     * @param version    the semantic version string
     * @return {@code true} if a template was removed, {@code false} if not found
     */
    boolean remove(String templateId, String version);

    /**
     * Returns a no-op implementation that always returns empty and discards registrations.
     * Useful as a safe default in tests and single-cycle agents that rely entirely on
     * Pattern 1 (system prompt injection) and Pattern 2 (ToolRegistry).
     */
    static ProceduralStore noop() {
        return new ProceduralStore() {
            @Override public void register(WorkflowTemplate t) {}
            @Override public Optional<WorkflowTemplate> retrieve(String taskType) { return Optional.empty(); }
            @Override public Optional<WorkflowTemplate> findById(String id, String v) { return Optional.empty(); }
            @Override public List<WorkflowTemplate> listAll() { return List.of(); }
            @Override public boolean remove(String id, String v) { return false; }
            @Override public String toString() { return "ProceduralStore.noop()"; }
        };
    }
}
