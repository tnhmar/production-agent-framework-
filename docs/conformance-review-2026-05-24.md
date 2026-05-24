# Production Agent Framework — Full Conformance Review

**Date:** 2026-05-24  
**Reviewer:** Perplexity AI (automated deep-read pass)  
**Scope:** All production source packages + test suite  
**Status legend:** ✅ Conforms · ⚠️ Advisory · 🔴 Defect

---

## 1. Executive Summary

The framework is architecturally sound and implements the full agent lifecycle (Perception → Reasoning → Action → Review) correctly. Security layers, liveness detectors, snapshot integrity, and the multi-tier working-memory eviction model are all present and well-wired. The findings below are a mix of genuine defects, safety advisories, and documentation notes. No critical security holes were found; the most impactful issues are around concurrency safety in `DefaultWorkingMemory` and the absence of `validateParallel` usage inside `DefaultAction`.

---

## 2. Package-by-Package Findings

### 2.1 `com.agentframework.core`

#### 2.1.1 `StateMachineRunner` ✅
- The cycle-record ordering fix is correctly applied: `recordCycle()` and `incrementCycle()` are called **unconditionally** at the end of every `PLANNING` step before the `isLive()` guard, ensuring the terminal cycle (e.g., `FinalAnswer`) is always persisted.
- `TaintClassifier` is correctly injected as a single shared instance rather than constructed inline inside `Review`, preventing redundant pattern compilation per cycle.
- `SUSPENDED_HITL` and `WAITING_FOR_JOB` states correctly delegate to `AsyncAgentRuntime` with a clear `TerminationReason.Escalated`.
- Resource limits (cycles, tokens, wall-clock, budget) are checked in `VALIDATING` before every cycle.

#### 2.1.2 `DefaultExecutionContext` ✅
- SHA-256 snapshot integrity hash covers all mutable fields: run identity, goal stack, working memory, beliefs, liveness counters, token/cost accumulators. The canonical string format is deterministic.
- `restoreFromSnapshot` correctly restores **all** liveness counters (`consFailures`, `stagnantCycles`, `stuckCycles`, `revisions`), preventing counter-reset abuse on resume.
- `SNAPSHOT_SCHEMA_VERSION = "1.1"` is defined as a constant for drift detection.

⚠️ **Advisory (N-EC-1):** `addTokens(int n)` and `addCost(BigDecimal c)` are not thread-safe — they perform read-modify-write on primitive `int` and `BigDecimal` fields without synchronisation. In async / parallel tool execution these accumulators can lose updates. Recommend `AtomicInteger` for `totalTokens` and a `synchronized` or `AtomicReference<BigDecimal>` for `totalCost`.

#### 2.1.3 `DefaultWorkingMemory` ✅ / ⚠️
- M2 fix: `evictLowestRelevance(int n)` is tier-aware. Eviction order: `ARCHIVED(0) → COMPRESSED(1) → BACKGROUND(2) → ACTIVE(3)`; within a tier, ascending relevance score.
- IC4 fix: evicted entries are removed from the `processed` set, preventing ghost references in `getUnprocessed()`.
- `compress()` also calls `processed.remove(id)` — consistent with IC4.

🔴 **Defect (WM-1):** `evictLowestRelevance` and `evictOldest` synchronise on `entries` (a `Collections.synchronizedList`), but `add()` does **not** synchronise on the same monitor. Callers from parallel tool execution threads can call `add()` concurrently with an eviction, resulting in a `ConcurrentModificationException` or silent data loss. All mutation paths should use the same lock, or the list should be replaced with a `CopyOnWriteArrayList` (acceptable since writes are far less frequent than reads in working memory).

⚠️ **Advisory (WM-2):** `estimatedTokenCount()` uses `content.length() / 4` as a proxy for token count. This is a rough estimate (actual tokenisation is model-dependent). Consider making the divisor configurable via a `TokenEstimator` strategy to avoid hard-evicting entries that are under the real token budget.

#### 2.1.4 `DefaultBeliefState` ✅
- Uses `ConcurrentHashMap.compute()` for atomic read-modify-write — correct.
- Conflict resolution (winner by confidence) and `withConflicted(true)` tagging are applied correctly.
- `conflictLog` uses `CopyOnWriteArrayList` — safe for concurrent append.

#### 2.1.5 `DefaultGoalStack` ⚠️
- Not thread-safe: `stack` (`ArrayDeque`) and `allGoals` (`LinkedHashMap`) are used without synchronisation. In the current single-threaded state machine this is safe, but async orchestrators accessing the goal stack concurrently (e.g., during HITL resume) could corrupt state.

⚠️ **Advisory (GS-1):** Wrap `push`, `pop`, `updateStatus`, `current`, and `allActive` in `synchronized(this)` blocks, or replace with concurrent alternatives, to future-proof against async access patterns.

#### 2.1.6 `DefaultLivenessDetector` ✅
- N1 stagnation: SHA-256 goal-state hash comparison over `id=status` pairs — deterministic and tamper-evident.
- N2 stuck-state: fires after `maxStuckCycles` consecutive cycles with no substantive decision.
- Both thresholds are constructor-configurable (production defaults: stagnation=3, stuck=2).
- `isSubstantiveDecision` correctly recognises `ToolCall`, `ParallelToolCalls`, `FinalAnswer`, `Escalate`.

#### 2.1.7 `ContextWindowManager` ✅
- Eviction threshold = 70% of `maxTokens`; evicts the lower-half by relevance.
- Guard `if (maxTokens <= 0) return` prevents divide-by-zero.
- Injected into `Review` — not constructed inline — allowing test overrides.

#### 2.1.8 `Review` ✅
- Taint classification happens **before** the entry is written to working memory.
- `HOSTILE` taint triggers a `HOSTILE_TAINT_DETECTED` event.
- Context window management runs after working memory write.
- Long-term memory write-back is guarded by `indicatesWorldChange()` and catches all exceptions silently — appropriate for a best-effort episodic write.
- Post-action re-validation with `validateAfterAction` and revision budget (max 3) is correctly implemented.
- Consecutive failure counter is incremented for `ActionResult.Failure` and `ValidationFailure`; reset on success.

🔴 **Defect (RV-1):** In `checkTermination`, `ActionResult.Escalated` and `ActionResult.Clarification` do **not** reset the consecutive failure counter, even though they are not failures. If these result types appear repeatedly they will increment `consFailures` indirectly (they fall through neither the failure branch nor the reset branch). The reset `else` block should cover all non-failure variants.

#### 2.1.9 `ValidationResult` ✅
- Sealed interface with `Passed`, `Failed`, `NeedsCorrection` permits — exhaustive pattern matching enforced by the compiler.

#### 2.1.10 `CompositePlanValidator` ✅
- Short-circuit chain: first non-`Passed` result stops evaluation.
- Empty validator list throws `IllegalArgumentException` at construction time.
- `List.copyOf` ensures immutability of the validator chain.

#### 2.1.11 `GoalStatus` ✅
- Five statuses: `PENDING`, `ACTIVE`, `COMPLETED`, `FAILED`, `DEFERRED` — matches the specification table.

#### 2.1.12 `WorkingMemoryTier` ✅
- `SECONDARY` is deprecated (binary-compatibility alias for `BACKGROUND`) — appropriate migration path.
- Javadoc eviction priority order is correct and matches `DefaultWorkingMemory.tierPriority()`.

#### 2.1.13 `PassThroughPlanValidator` ⚠️
- Javadoc says "Replace for production rule enforcement" — correct advisory, but there is no compile-time or runtime guard preventing its use in a production `AgentRuntime`. Consider a `@VisibleForTesting` annotation or a `DEBUG` flag check to make accidental production use louder.

---

### 2.2 `com.agentframework.action`

#### 2.2.1 `DefaultAction` ✅ / 🔴
- Canonical factory `withDefaultValidators` wires the full 5-layer validation stack in the correct order: `SchemaActionValidator → SemanticActionValidator → SafetyActionValidator → SecurityEnforcer → TaintActionValidator`.
- `FinalAnswer` and `AskClarification`/`Escalate` are handled in the `execute` switch — no missing cases.
- Parallel `executor` is an instance-scoped fixed-thread-pool (4 threads, daemon) — correct (no static field).

🔴 **Defect (DA-1):** `executeParallel` calls each `ActionValidator.validate(tc, contract, ctx)` in the **pre-flight loop** but does **not** call `SecurityEnforcer.validateParallel(...)`. The IC6 fix exists on `SecurityEnforcer` but is never invoked from `DefaultAction`. As a result, parallel batches bypass the hostile-taint-in-working-memory guard and the per-tenant irreversible-action check. Fix: call `securityEnforcer.validateParallel(calls, registry::lookup, ctx)` before the fan-out.

⚠️ **Advisory (DA-2):** The `executor` (fixed pool of 4) is not shut down in any `close()` / `AutoCloseable` lifecycle method. In long-lived applications or test suites this leaks daemon threads. Implement `AutoCloseable` and call `executor.shutdownNow()` on close.

⚠️ **Advisory (DA-3):** `executeParallel` uses `p.deadline().toMillis()` as the per-future `get()` timeout but does not account for wall-clock drift between futures. If future 0 takes `deadline` milliseconds, future 1 also waits another `deadline` milliseconds, making the effective worst-case wall-clock time `calls.size() × deadline`. Consider tracking elapsed time or using a global deadline `CompletableFuture.allOf` with a single timeout.

#### 2.2.2 `ToolContract` ✅
- `HIGH_BLAST_RADIUS` is present in the `SideEffectClass` enum (m7 fix).
- `version` field enables schema-drift detection in CI.
- Static factory methods `readOnly`, `write`, `irreversible`, `highBlastRadius` cover the four most common patterns.

#### 2.2.3 `SafetyActionValidator` ✅
- Correctly returns `requireApproval` for `IRREVERSIBLE` and `HIGH_BLAST_RADIUS`.
- `null` contract guard is present.

#### 2.2.4 `ToolException` ✅
- Carries a structured `errorCode` — propagated through `DefaultAction` to `ActionResult.Failure`.

---

### 2.3 `com.agentframework.security`

#### 2.3.1 `TaintClassifier` ✅
- Patterns compiled once at class load (`List.of(Pattern.compile(...))`).
- Covers: instruction-override, role/persona override, system-prompt delimiter injection (ChatML, Anthropic, custom), prompt-leakage probes, jailbreak tokens, payload-framing overrides.
- All patterns use `(?is)` flags — case-insensitive + DOTALL for multi-line payloads.
- `null`/blank input → `CLEAN` (safe default).
- `classifyObject` handles `null` safely.

⚠️ **Advisory (TC-1):** The pattern for role/persona override (`"you are now ... model"`) requires the word `model`, `assistant`, `ai`, etc. at the end. Attackers using `"You are now DAN"` without that suffix would not match the role pattern (though `DAN` is caught by a separate jailbreak token pattern). Review coverage with a dedicated red-team test set.

#### 2.3.2 `SecurityEnforcer` ✅
- Correctly separates tenant-policy check, hostile-taint check, and irreversible-action check.
- IC2 note (comment in code): `SafetyActionValidator` runs before `SecurityEnforcer` and emits `REQUIRE_APPROVAL`; `SecurityEnforcer` hard-fails only when the tenant policy explicitly disallows irreversible actions. This two-layer design is intentional and correct.
- `validateParallel` implements IC6 fix for parallel batches — but see Defect DA-1 above: it is never called.

---

### 2.4 `com.agentframework.observability`

#### 2.4.1 `AgentEvent` / `InMemoryEventSink` ✅
- `EventType` enum covers the full lifecycle: run, cycle, tool, plan, belief, HITL, memory, context, security, liveness, multi-agent events.
- `InMemoryEventSink` uses `CopyOnWriteArrayList` — thread-safe for concurrent emitters.
- `clear()` method available for test isolation.

⚠️ **Advisory (OB-1):** `InMemoryEventSink` is unbounded — in long-running production agents it will grow indefinitely and eventually cause an `OutOfMemoryError`. Add a capacity cap (e.g., circular buffer of last N events) or a `drain()` method for production use.

#### 2.4.2 `AgentRuntime` ✅
- Async thread pool is **instance-scoped** (P5 fix) — no static shared pool.
- `executeWith(Agent, DefaultExecutionContext)` exposes the live context for `AsyncAgentRuntime` checkpoint access.
- `replay` verifies SHA-256 integrity before restoring state — tampered snapshots are rejected.
- `buildReplayTask` falls back to `"(replay — instruction unavailable)"` if no `root` goal is found.

⚠️ **Advisory (RT-1):** The `asyncPool` (cached thread pool in the 2-arg constructor) is not shut down anywhere. Same concern as DA-2 above. Implement `AutoCloseable`.

---

### 2.5 `com.agentframework.perception`

#### 2.5.1 `SimplePerception` ✅
- Drains unprocessed `USER` and `TOOL` entries from working memory.
- Falls back to the current goal description as a seed observation when the queue is empty.
- Marks entries processed after observation — prevents duplicate processing.

⚠️ **Advisory (PE-1):** `SimplePerception` assigns `TrustTier.HIGH` to all working-memory entries regardless of their `taintLabel`. A `HOSTILE`-tainted entry retrieved from working memory and passed to the reasoning engine with `TrustTier.HIGH` elevates its perceived trustworthiness. Consider mapping `TaintLabel.HOSTILE → TrustTier.UNTRUSTED` and `TaintLabel.EXTERNAL → TrustTier.LOW` in `SimplePerception`.

---

### 2.6 `com.agentframework.multi`

#### 2.6.1 Package structure ✅
- `AgentCard`, `AgentHandle`, `AgentOrchestrator`, `PipelineOrchestrator`, `SupervisorOrchestrator`, `A2AClient`, `A2ATask` — full A2A topology is represented.
- `Capabilities`, `Skill`, `SecurityScheme`, `TaskSpec`, `TaskTrace`, `MultiAgentResult` — supporting value types are present.

⚠️ **Advisory (MA-1):** `A2AClient` is a stub interface (142 bytes). There is no HTTP transport, authentication, or retry logic in the codebase. For a production deployment this must be implemented with mTLS, token-based auth, and idempotency tokens before multi-agent delegation is used in any real environment.

---

## 3. Cross-Cutting Findings

### 3.1 Test Coverage

The test suite is extensive:

| Test file | Primary area |
|---|---|
| `CoreTest.java` | State machine, goal stack, snapshots |
| `CoreCoverageTest.java` | Branch coverage of core classes |
| `RuntimeTest.java` | AgentRuntime sync/async/replay |
| `RuntimeCoverageTest.java` | Edge cases, HITL, resource limits |
| `ActionTest.java` | Validation stack, parallel execution |
| `ExtendedCoverageTest.java` | Extended branch coverage |
| `PlanValidatorTest.java` | CompositePlanValidator, GoalCoherence |
| `SecurityTest.java` | TaintClassifier, SecurityEnforcer |
| `PerceptionTest.java` | DefaultPerception, stages |
| `MemoryTest.java` | Tiered memory, eviction |
| `TieredMemoryCoverageTest.java` | Working memory tier coverage |
| `HitlTest.java` | HITL suspension and resume |
| `MultiAgentTest.java` | Orchestrators |
| `MultiAgentCoverageTest.java` | A2A, delegation depth |
| `ObservabilityExtendedTest.java` | Event sink, metrics |
| `ObservabilityPerceptionTest.java` | Perception + observability integration |
| `ReasoningTest.java` | Reasoning engine integration |
| `RagTest.java` | RAG pipeline |
| `FoundationTest.java` | Foundation value types |

⚠️ **Advisory (TST-1):** No test directly verifies Defect DA-1 (i.e., that `SecurityEnforcer.validateParallel` is exercised during a `ParallelToolCalls` execution with hostile taint in working memory). A regression test should be added to `ActionTest` or `SecurityTest`.

⚠️ **Advisory (TST-2):** No test exercises concurrent `add()` + `evictLowestRelevance()` on `DefaultWorkingMemory` to catch Defect WM-1. A stress test with 10+ concurrent threads should be added.

### 3.2 Snapshot Schema Versioning

`SNAPSHOT_SCHEMA_VERSION = "1.1"` is defined on `DefaultExecutionContext` but the `Snapshot` interface's `schemaVersion()` method is not validated during `restoreFromSnapshot` or `replay`. If a snapshot at schema `"1.0"` (missing the `revisionCount` field) is replayed, silent defaults will be used.

⚠️ **Advisory (SN-1):** Add a schema version check in `AgentRuntime.verifyIntegrity` (or a dedicated `validateSchemaVersion` method) that rejects or migrates snapshots with an unsupported schema version.

### 3.3 `PassThroughPlanValidator` in Production

The only concrete `PlanValidator` shipped in `core` is `PassThroughPlanValidator` (approves everything). `AgentRuntime`'s public 1-arg constructor accepts any `PlanValidator`, and nothing prevents passing a `PassThroughPlanValidator` in production.

⚠️ **Advisory (PV-1):** Ship at least one enforcement-grade validator (e.g., `GoalCoherencePlanValidator`) in the `core` package and update the `AgentRuntime` factory methods to require it by default, with an explicit opt-out for testing.

---

## 4. Defect Register

| ID | Severity | Component | Description |
|---|---|---|---|
| **DA-1** | 🔴 High | `DefaultAction` | `SecurityEnforcer.validateParallel` is never called; parallel batches bypass hostile-taint and irreversible-action checks |
| **RV-1** | 🔴 Medium | `Review` | `ActionResult.Escalated` / `Clarification` do not reset `consFailures`; can cause premature failure escalation |
| **WM-1** | 🔴 Medium | `DefaultWorkingMemory` | `add()` not synchronised on the same monitor as `evictLowestRelevance()`; race condition under parallel tool execution |

---

## 5. Advisory Register

| ID | Component | Advisory |
|---|---|---|
| N-EC-1 | `DefaultExecutionContext` | `addTokens`/`addCost` not thread-safe under parallel tool execution |
| WM-2 | `DefaultWorkingMemory` | Token estimation divisor `4` is hard-coded; make configurable |
| GS-1 | `DefaultGoalStack` | Not thread-safe; HITL async resume could corrupt state |
| DA-2 | `DefaultAction` | `executor` never shut down; implement `AutoCloseable` |
| DA-3 | `DefaultAction` | Parallel deadline logic is per-future, not global |
| RT-1 | `AgentRuntime` | `asyncPool` never shut down; implement `AutoCloseable` |
| TC-1 | `TaintClassifier` | Role-override pattern requires model-class suffix; add red-team test |
| OB-1 | `InMemoryEventSink` | Unbounded; cap or drain for production use |
| PE-1 | `SimplePerception` | `HOSTILE` taint mapped to `TrustTier.HIGH`; should map to `UNTRUSTED` |
| MA-1 | `A2AClient` | Stub only; no transport/auth for production A2A |
| TST-1 | `ActionTest` | Missing regression test for DA-1 (parallel + hostile taint) |
| TST-2 | `MemoryTest` | Missing concurrency stress test for WM-1 |
| SN-1 | `AgentRuntime` | Snapshot schema version not validated on replay |
| PV-1 | `AgentRuntime` | `PassThroughPlanValidator` is too easily used in production |

---

## 6. Positive Highlights

- **SHA-256 integrity on snapshots** — full coverage of all mutable fields including liveness counters; tamper-evident replay.
- **Sealed `ValidationResult` and `TerminationReason`** — compiler-enforced exhaustive handling.
- **Tier-aware working memory eviction (M2)** — `ARCHIVED → COMPRESSED → BACKGROUND → ACTIVE` priority is correctly implemented and documented.
- **Single `TaintClassifier` instance per `StateMachineRunner`** — patterns compiled once; no per-cycle overhead.
- **IC4 ghost-reference fix** — evicted entries cleaned from the `processed` set in both `evictFirst` and `compress`.
- **Cycle record always written including terminal cycle** — `finalAnswer()` and `cycleRecords()` on `ExecutionResult` are always populated.
- **Instance-scoped async pool (P5)** — no cross-tenant thread starvation.
- **Comprehensive test suite** — 19 test classes covering all packages.
