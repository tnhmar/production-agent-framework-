package com.agentframework.core;

import com.agentframework.foundation.ProceduralStore;
import com.agentframework.foundation.WorkflowTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Default in-process implementation of {@link ProceduralStore}.
 *
 * <p>Satisfies <strong>Vol.1 Ch.10 Pattern 3</strong> at the framework level: workflow templates
 * are stored in a keyed map and retrieved by exact task-type match. This is the correct starting
 * point per the Vol.1 tip box (secprocedural):
 * <blockquote>
 *   "Start with system prompt injection for stable, universal procedures and workflow templates
 *   in an external store for task-specific or user-personalised ones."
 * </blockquote>
 *
 * <p><strong>OCP extension path:</strong> A future {@code VectorProceduralStore} in the
 * {@code rag} module will implement {@link ProceduralStore} with similarity-based retrieval
 * for deployments that need per-user personalisation or large template catalogues. Zero
 * modification to this class or to {@link ProceduralStore} will be required.
 *
 * <p><strong>Write-gate implementation:</strong>
 * <ol>
 *   <li>Duplication check: if a template with identical {@code templateId + version} already
 *       exists, the new registration is rejected with a warning audit entry.</li>
 *   <li>Significance: always passes in this implementation (in-memory cost is negligible).</li>
 *   <li>Sensitivity: not enforced here — callers are responsible for stripping credentials
 *       before registering templates in production deployments.</li>
 * </ol>
 *
 * <p><strong>Audit:</strong> Every mutating operation (register, remove) appends a structured
 * entry to an in-memory audit log accessible via {@link #getAuditLog()}. In production,
 * replace with a durable append-only store.
 *
 * <p>Thread-safe: all mutations are synchronised on {@code this}.
 */
public final class InMemoryProceduralStore implements ProceduralStore {

    private static final Logger LOG = Logger.getLogger(InMemoryProceduralStore.class.getName());

    /** Primary index: templateId -> version -> template. */
    private final Map<String, Map<String, WorkflowTemplate>> byId = new ConcurrentHashMap<>();

    /**
     * Secondary index: taskType -> latest registered template.
     * "Latest" is defined by registration order (last-write-wins per task type).
     */
    private final Map<String, WorkflowTemplate> byTaskType = new ConcurrentHashMap<>();

    /** Write-immutable audit log entries. Append-only. */
    private final List<AuditEntry> auditLog = Collections.synchronizedList(new ArrayList<>());

    // -------------------------------------------------------------------------
    // ProceduralStore implementation
    // -------------------------------------------------------------------------

    @Override
    public synchronized void register(WorkflowTemplate template) {
        if (template == null) throw new IllegalArgumentException("template must not be null");

        // --- Write-gate: duplication check ---
        Map<String, WorkflowTemplate> versions =
                byId.computeIfAbsent(template.getTemplateId(), k -> new LinkedHashMap<>());

        if (versions.containsKey(template.getVersion())) {
            String msg = "Duplicate registration rejected: templateId='" + template.getTemplateId()
                    + "' version='" + template.getVersion() + "'";
            LOG.warning(msg);
            auditLog.add(new AuditEntry("REGISTER_REJECTED_DUPLICATE",
                    template.getTemplateId(), template.getVersion(), Instant.now()));
            return;
        }

        versions.put(template.getVersion(), template);
        byTaskType.put(template.getTaskType(), template); // last-write-wins per taskType

        auditLog.add(new AuditEntry("REGISTER",
                template.getTemplateId(), template.getVersion(), Instant.now()));

        LOG.fine("Registered procedural template: " + template);
    }

    @Override
    public Optional<WorkflowTemplate> retrieve(String taskType) {
        if (taskType == null) return Optional.empty();
        return Optional.ofNullable(byTaskType.get(taskType));
    }

    @Override
    public Optional<WorkflowTemplate> findById(String templateId, String version) {
        if (templateId == null || version == null) return Optional.empty();
        Map<String, WorkflowTemplate> versions = byId.get(templateId);
        if (versions == null) return Optional.empty();
        return Optional.ofNullable(versions.get(version));
    }

    @Override
    public List<WorkflowTemplate> listAll() {
        List<WorkflowTemplate> all = new ArrayList<>();
        byId.values().forEach(versions -> all.addAll(versions.values()));
        // sort by registeredAt ascending
        all.sort((a, b) -> a.getRegisteredAt().compareTo(b.getRegisteredAt()));
        return Collections.unmodifiableList(all);
    }

    @Override
    public synchronized boolean remove(String templateId, String version) {
        if (templateId == null || version == null) return false;
        Map<String, WorkflowTemplate> versions = byId.get(templateId);
        if (versions == null || !versions.containsKey(version)) return false;

        // Fix DLS_DEAD_LOCAL_STORE: do not capture the return value — the
        // side-effect of removal from the map is all that is needed here.
        versions.remove(version);
        if (versions.isEmpty()) byId.remove(templateId);

        // Remove from taskType index if this was the latest for that type
        byTaskType.entrySet().removeIf(e ->
                e.getValue().getTemplateId().equals(templateId)
                        && e.getValue().getVersion().equals(version));

        auditLog.add(new AuditEntry("REMOVE", templateId, version, Instant.now()));
        LOG.fine("Removed procedural template: id='" + templateId + "' v=" + version);
        return true;
    }

    // -------------------------------------------------------------------------
    // Audit
    // -------------------------------------------------------------------------

    /**
     * Returns an unmodifiable snapshot of the audit log.
     * Entries are append-only and write-immutable.
     */
    public List<AuditEntry> getAuditLog() {
        return Collections.unmodifiableList(new ArrayList<>(auditLog));
    }

    // -------------------------------------------------------------------------
    // Audit entry value type
    // -------------------------------------------------------------------------

    /**
     * A single write-immutable audit record for a procedural store mutation.
     * Captures the operation type, the affected template identity, and the wall-clock time.
     */
    public static final class AuditEntry {
        private final String operation;   // REGISTER | REGISTER_REJECTED_DUPLICATE | REMOVE
        private final String templateId;
        private final String version;
        private final Instant timestamp;

        public AuditEntry(String operation, String templateId, String version, Instant timestamp) {
            this.operation  = operation;
            this.templateId = templateId;
            this.version    = version;
            this.timestamp  = timestamp;
        }

        public String getOperation()  { return operation; }
        public String getTemplateId() { return templateId; }
        public String getVersion()    { return version; }
        public Instant getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return timestamp + " [" + operation + "] id='" + templateId + "' v=" + version;
        }
    }

    @Override
    public String toString() {
        return "InMemoryProceduralStore{templates=" + listAll().size() +
               ", auditEntries=" + auditLog.size() + "}";
    }
}
