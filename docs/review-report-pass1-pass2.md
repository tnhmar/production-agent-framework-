# Production-Grade Review Report — Pass 1 & Pass 2

## Repository: `tnhmar/production-agent-framework-`
## Spec: Volume 1 — *Architecting AI Agent Systems*

---

## PASS 1 — Specification Conformance Audit

---

### 1.1 — State Machine

**Check: All states present**  
`RunState` contains: `INITIALIZED`, `VALIDATING`, `PLANNING`, `MODEL_CALL`, `TOOL_EXECUTION`, `MEMORY_UPDATE`, `RESPONDING`, `AWAITING_RESULT`, `DEGRADED`, `SUSPENDED_HITL`, `WAITING_FOR_JOB`, `COMPLETED`, `ABORTED`, `TERMINATED`. ✅ All spec-required states are present. `RESPONDING` and `AWAITING_RESULT` are present as additions not conflicting with spec.

**Check: Every transition is guarded**  
In `StateMachineRunner.step()`, the `PLANNING` case does guard every `transitionTo()` call. However `RESPONDING` and `AWAITING_RESULT` — both present in `RunState` — have **no handler in the switch at all**. They fall through to the `default -> {}` branch.

---

#### FINDING-001

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.StateMachineRunner` |
| **Method** | `step(Agent, ExecutionContext)` |
| **Severity** | `[BLOCKER]` |
| **Spec ref** | Vol. 1 §"State Transitions and the State Machine Model" |

**Problem**  
States `RESPONDING` and `AWAITING_RESULT` are declared in `RunState` but have no case in `StateMachineRunner.step()`; if the context ever enters either state the runner silently loops forever because `isLive()` returns `true` and `step()` does nothing — infinite spin without termination.

**Evidence**  
`RunState.java` — `RESPONDING`, `AWAITING_RESULT` are live states (`isTerminal` returns `false`).  
`StateMachineRunner.step()` — `default -> {}` swallows them.

**Fix**  
Either add explicit case handlers that terminate with a typed reason, or (correct path) throw:

```java
default -> throw new IllegalStateException(
    "StateMachineRunner has no handler for live state: "
    + ctx.currentState() + " runId=" + ctx.runId());
```

**Test to add**
- `StateMachineRunnerTest#unhandledLiveState_throwsIllegalState`

---

**Check: DEGRADED path**  
`StateMachineRunner` handles `DEGRADED` by calling `terminate()` → `TERMINATED` with `FailureEscalation`. The spec says the agent must either recover or be explicitly aborted; `ABORTED` is a distinct terminal state. The implementation always uses `TERMINATED`, never `ABORTED`.

---

#### FINDING-002

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.StateMachineRunner` |
| **Method** | `step(Agent, ExecutionContext) — DEGRADED case` |
| **Severity** | `[BLOCKER]` |
| **Spec ref** | Vol. 1 §"State Transitions and the State Machine Model" |

**Problem**  
`DEGRADED → TERMINATED` is used instead of the spec-required `ABORTED` terminal state. `ABORTED` exists in `RunState` but is never reached by any production code path, making it dead code. The semantic distinction matters: `TERMINATED` means "completed abnormally by resource limit or policy"; `ABORTED` means "system-level shutdown of the agent"; conflating them removes the ability to route incidents correctly at the operator level.

**Evidence**  
`StateMachineRunner.java` — `ctx.transitionTo(RunState.TERMINATED)` in the `DEGRADED` case. `RunState.ABORTED` is never written anywhere in production code.

**Fix**  
Change `DEGRADED` case to `ctx.transitionTo(RunState.ABORTED)`. Verify `isTerminal()` returns `true` for `ABORTED`. Update `ExecutionResult.succeeded()` to also exclude `ABORTED`.

**Test to add**
- `StateMachineRunnerTest#degradedState_transitionsToAborted_notTerminated`

---

**Check: default branch is silent**

#### FINDING-003

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.StateMachineRunner` |
| **Method** | `step(Agent, ExecutionContext) — default case` |
| **Severity** | `[BLOCKER]` |
| **Spec ref** | Vol. 1 §"State Transitions and the State Machine Model" |

**Problem**  
The switch `default` branch is `default -> {}` — a silent no-op. Any state added to `RunState` without a corresponding handler produces an infinite live loop with no diagnostic. This is a class of bug that is structurally impossible to detect at runtime.

**Evidence**  
`StateMachineRunner.java` — `default -> {}`

**Fix**

```java
default -> throw new IllegalStateException(
    "StateMachineRunner has no handler for live state: "
    + ctx.currentState() + " runId=" + ctx.runId());
```

**Test to add**
- `StateMachineRunnerTest#defaultBranch_withFutureState_throwsIllegalState`

---

### 1.2 — Goal Stack

**Check: Seven required fields** ✅  
`Goal` record has: `id`, `parentId`, `status`, `description`, `dependencies`, `allocatedBudget`, `successCriteria`, `excludedTools`, `requiredTools` — 9 fields total. All 7 spec-required fields are present.

**Check: GoalStatus has DEFERRED** ✅  
`GoalStatus` enum: `PENDING, ACTIVE, COMPLETED, FAILED, DEFERRED`.

**Check: DEFERRED has resumeCondition**  
`GoalStatus` is a plain enum with no fields. `DEFERRED` carries no `resumeCondition` expression. `GoalStack` has no `defer(goalId, resumeCondition)` method.

---

#### FINDING-004

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.GoalStatus` / `GoalStack` |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Goal Stack and Subgoal Tracking" |

**Problem**  
`GoalStatus.DEFERRED` exists as an enum constant but carries no `resumeCondition`. The spec requires that deferred goals hold a typed resume condition so the orchestrator can re-activate them when the condition becomes true. Without this, `DEFERRED` is semantically identical to `FAILED` — the goal is dropped, not paused.

**Evidence**  
`GoalStatus.java` — plain enum, no fields. No `defer(id, condition)` method found in `GoalStack`.

**Fix**  
Either:  
(a) Convert `GoalStatus` to a sealed interface with a `Deferred(String resumeCondition)` record, or  
(b) Add a `Map<String,String> deferredConditions` map to `GoalStack` with `defer(goalId, resumeCondition)` / `tryResume(ctx)` methods.

**Test to add**
- `GoalStackTest#defer_withCondition_resumesWhenConditionHolds`

---

**Check: stepBudget field on Goal** ✅  
`Goal` has `allocatedBudget` of type `Budget` (which contains `maxCycles`, `maxTokens`, `maxWallClockTime`, `budgetLimit`). Covered by `Budget.maxCycles`.

**Check: Intent anchoring**  
No intent-anchoring check found in `StateMachineRunner`, `Review`, `LivenessDetector`, or any other class — no periodic check that active subgoal is still aligned with the top-level goal.

---

#### FINDING-005

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.DefaultLivenessDetector` (inferred) |
| **Method** | N/A — feature absent |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Goal Stack and Subgoal Tracking" |

**Problem**  
No intent-anchoring check exists. The spec requires periodic verification that the active subgoal is still aligned with the top-level task instruction. Without this, an agent can pursue a locally coherent subgoal that has drifted from the original user intent for an unbounded number of cycles.

**Evidence**  
`StateMachineRunner`, `Review`, `DefaultLivenessDetector` — no "top-level alignment" or "intent anchor" logic found anywhere.

**Fix**  
Add `LivenessDetector.checkIntentAlignment(topLevelGoal, activeGoal, ctx)` called once per `PLANNING` cycle after decision is made. Return `Optional<TerminationReason>` analogous to `checkStuck`/`checkStagnation`. Emit `GOAL_INTENT_DRIFT` event when triggered.

**Test to add**
- `LivenessDetectorTest#intentAlignment_driftedSubgoal_returnsReason`

---

### 1.3 — Belief State

**Check: Provenance fields** ✅  
`Belief` record references `incoming.provenance()`, `incoming.beliefId()`, `incoming.subject()`, `incoming.predicate()`, `incoming.object()`, `incoming.confidence()`, `incoming.withConflicted()` — all provenance-relevant fields appear present.

**Check: Confidence annotation** ✅  
`incoming.confidence()` is used as a `double` comparison in `assertBelief`.

**Check: Conflict detection — silent overwrite** ✅  
`DefaultBeliefState.assertBelief()` detects conflicts: when `existing.object()` differs from `incoming.object()`, it logs to `conflictLog` and marks the winner `.withConflicted(true)`. No silent overwrite.

**Check: BELIEF_CONFLICT event emission**  
The conflict IS detected in `DefaultBeliefState` and logged to `conflictLog`. However, the event is not emitted there — event emission happens in `Review.updateBeliefs()` which calls `assertBelief()` and checks the returned `won.conflicted()` flag. This means conflicts that happen outside of `Review` never emit a `BELIEF_CONFLICT` event.

---

#### FINDING-006

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.DefaultBeliefState` |
| **Method** | `assertBelief(Belief)` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Belief State" |

**Problem**  
`BELIEF_CONFLICT` event emission is not self-contained in `DefaultBeliefState`. It is handled only by `Review.updateBeliefs()` inspecting the returned `Belief.conflicted()` flag. Any caller that calls `assertBelief()` directly (e.g., future multi-agent code, tests, or replay paths) will silently miss conflict events.

**Evidence**  
`DefaultBeliefState.java` — no `EventSink` field, no `emit()` call.  
`Review.java#updateBeliefs` — emits `BELIEF_CONFLICT` only when called from the Review path.

**Fix**  
Inject `EventSink` into `DefaultBeliefState` constructor. Emit `BELIEF_CONFLICT` from within `assertBelief()` when a conflict is detected. Remove duplicate emission from `Review.updateBeliefs()`.

**Test to add**
- `BeliefStateTest#assertBelief_conflict_emitsBeliefConflictEvent`

---

**Check: Separate BeliefState / WorkingMemory types** ✅  
`BeliefState` and `WorkingMemory` are distinct interfaces/classes with no inheritance.

---

### 1.4 — Action Safety

**Check: Reversibility enum on ToolContract**

#### FINDING-007

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.action.ToolContract` |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"The Action Layer — Tool Calling" |

**Problem**  
The `Reversibility` classification (read-only, idempotent-write, non-idempotent-write, irreversible) cannot be confirmed as a typed enum. The spec requires all four values as distinct, named types. Only two factory methods visible: `ToolContract.readOnly()` and `ToolContract.write()`.

**Fix**  
Confirm `ToolContract` has `enum Reversibility { READ_ONLY, IDEMPOTENT_WRITE, NON_IDEMPOTENT_WRITE, IRREVERSIBLE }`. Add `NON_IDEMPOTENT_WRITE` and `IRREVERSIBLE` factories if absent. Gate `IRREVERSIBLE` tools with `ctx.checkpoint()` before dispatch.

**Test to add**
- `ToolContractTest#reversibilityEnum_allFourValuesPresent`

---

**Check: Layered validation pipeline (schema → semantic → policy → safety)**  
`CompositePlanValidator` chains validators in order and short-circuits on first non-`Passed`. However, there is no typing of which layer each validator represents.

#### FINDING-008

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.CompositePlanValidator` |
| **Method** | `validate(Decision, ExecutionContext)` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Action Validation" |

**Problem**  
The composite pipeline exists but the four spec-required layers (schema, semantic, policy, safety) are not typed. All validators are the same `PlanValidator` interface. An operator cannot configure or disable individual layers by type.

**Fix**  
Define `enum ValidationLayer { SCHEMA, SEMANTIC, POLICY, SAFETY }`. Add `ValidationLayer layer()` method to `PlanValidator` interface. `CompositePlanValidator` can then sort by layer order, verify all four are present, and allow layer-specific bypass flags per execution context.

**Test to add**
- `CompositePlanValidatorTest#allFourLayers_presentAndOrderedCorrectly`

---

**Check: RequiresApproval validation result variant**  
`ValidationResult` is a sealed interface with only three permits: `Passed`, `Failed`, `NeedsCorrection`. `RequiresApproval` is **absent**.

#### FINDING-009

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.ValidationResult` |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Action Validation" |

**Problem**  
`ValidationResult` sealed interface has no `RequiresApproval` variant. The spec explicitly states validators must return PASS, FAIL, or REQUIRE_APPROVAL. Without this variant, irreversible or high-blast-radius actions either pass silently or terminate the agent — there is no "pause and wait for approval" path.

**Evidence**  
`ValidationResult.java` — sealed permits `Passed`, `Failed`, `NeedsCorrection` only.

**Fix**

```java
record RequiresApproval(String reason, String approvalToken)
    implements ValidationResult {}
```

Update `StateMachineRunner` `PLANNING` case to handle `RequiresApproval` by transitioning to `SUSPENDED_HITL` with a structured approval request rather than to `TERMINATED`.

**Test to add**
- `PlanValidatorTest#requiresApproval_pausesRun_notTerminates`

---

### 1.5 — Working Memory & Context Window Management

**Check: Three-tier model (ACTIVE, SUMMARIZED, COMPRESSED)**  
Only `WorkingMemoryTier.ACTIVE` is used across all test and source code references found.

#### FINDING-010

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core` (WorkingMemoryTier) |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Working Memory" and §"Context Window Management" |

**Problem**  
Only `WorkingMemoryTier.ACTIVE` is used anywhere in production code. `SUMMARIZED` and `COMPRESSED` tiers either do not exist or are declared but dead. The spec requires active/summarized/compressed as a three-tier model where the eviction policy moves entries DOWN the tiers rather than deleting them outright. The current `ContextWindowManager.manage()` calls `wm.evictLowestRelevance(half)` which hard-deletes entries.

**Evidence**  
`ContextWindowManager.java` — `wm.evictLowestRelevance(half)` deletes, no tier demotion.  
`Review.java` and tests — only `WorkingMemoryTier.ACTIVE` used.

**Fix**
1. Confirm `WorkingMemoryTier` has `ACTIVE`, `SUMMARIZED`, `COMPRESSED`.
2. Replace evict with demote: `ACTIVE→SUMMARIZED` compresses content to key facts; `SUMMARIZED→COMPRESSED` replaces with a reference token.
3. `ContextWindowManager.manage()` should call `wm.demote(entry)` not `wm.evict(entry)`.

**Tests to add**
- `ContextWindowTest#eviction_demotesToSummarized_notDeleted`
- `ContextWindowTest#evictedEntry_absentFromNextPerception`

---

#### FINDING-011

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.ContextWindowManager` |
| **Method** | `manage(WorkingMemory, int)` |
| **Severity** | `[BLOCKER]` |
| **Spec ref** | Vol. 1 §"Working Memory — The In-Session Scratchpad" |

**Problem**  
`ContextWindowManager.manage()` is a 4-line method that hard-deletes the bottom half of working memory by relevance score when utilization exceeds 70%. It has no `prune(threshold)` method, no `summarize(entry)` method, no three-tier demotion logic, no configurable threshold, and no instrumented token-size emission. This is a naive stub — not a production context-window manager.

**Evidence**

```java
// ContextWindowManager.java — entire implementation:
if (wm.estimatedTokenCount() > (int)(0.70 * maxTokens))
    wm.evictLowestRelevance(half);
// No summarization, no tier demotion, no configurable parameters.
```

**Fix**  
Full rewrite required:
- Add `Summarizer` interface (injectable) with `summarize(String content, int maxTokens): String`
- Add `prune(WorkingMemory wm, int threshold): List<WorkingMemoryEntry>` that returns demoted entries instead of deleting
- Add `promote(WorkingMemoryEntry entry): void` for re-activation
- Emit `CONTEXT_WINDOW_PRESSURE` event when utilization crosses 70%
- Emit `CONTEXT_WINDOW_EVICTION` event per demoted entry
- Make `EVICTION_THRESHOLD` a constructor parameter (default 0.70)

**Tests to add**
- `ContextWindowTest#manage_aboveThreshold_emitsContextWindowPressure`
- `ContextWindowTest#manage_demotesEntries_insteadOfDeleting`

---

#### FINDING-012

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.StateMachineRunner` / `Review` |
| **Method** | `step()` / `step()` |
| **Severity** | `[MINOR]` |
| **Spec ref** | Vol. 1 §"Working Memory" |

**Problem**  
Working memory token size is not instrumented per cycle. The spec explicitly requires: "Instrument working memory size at every step. A chart of memory utilization across a run's lifecycle reveals accumulation patterns." No `WORKING_MEMORY_SIZE` event is emitted.

**Evidence**  
`StateMachineRunner.java` — `CYCLE_STARTED` emits cycle count only. `Review.java` — no `wm.estimatedTokenCount()` emit.

**Fix**  
In `StateMachineRunner` `PLANNING` case, after calling `ctxManager.manage()`, emit `AgentEvent.EventType.CYCLE_STARTED` with attributes including `"wmTokens" → ctx.workingMemory().estimatedTokenCount()`.

**Test to add**
- `ObservabilityExtendedTest#cycleStarted_includesWorkingMemoryTokenCount`

---

**Check: Perception filters by active goal, not exhaustive**

#### FINDING-013

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.perception.SimplePerception` |
| **Method** | `perceive(ExecutionContext)` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Context Window Management — Filling" |

**Problem**  
`SimplePerception` is used as the default perception in all tests and in the `Agent.builder()` pattern. Without reading its implementation, the risk of exhaustive (non-filtered) working-memory projection cannot be ruled out. The spec requires perception to select entries by relevance to the CURRENT active subgoal, not include all entries.

**Fix**  
Confirm `SimplePerception.perceive()` filters `WorkingMemoryEntry` list by scoring against `ctx.goalStack().current()`. If it includes all entries regardless of relevance, inject a `RelevanceScorer` and filter to top-K by score against the active goal description.

**Test to add**
- `PerceptionTest#perceive_filtersToActiveGoalRelevance_notExhaustive`

---

### 1.6 — Checkpointing

**Check: Checkpoint before irreversible action**

#### FINDING-014

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.StateMachineRunner` / `DefaultAction` |
| **Method** | `step() PLANNING case` / `execute(Decision, ExecutionContext)` |
| **Severity** | `[BLOCKER]` |
| **Spec ref** | Vol. 1 §"State Serialization and Checkpointing" |

**Problem**  
No checkpoint is taken before executing any action, irreversible or otherwise. The spec requires "checkpoint before every irreversible action." Without pre-action checkpointing, a failed irreversible action (e.g., send-email, financial write) leaves the agent in a state that cannot be replayed to the pre-action point for debugging or compensation.

**Evidence**  
`StateMachineRunner.java` `PLANNING` case — `agent.action().execute()` is called directly with no `ctx.checkpoint()` preceding it.

**Fix**

```java
// In StateMachineRunner PLANNING case, after validation passes:
if (isIrreversible(decision)) { ctx.checkpoint(); }
// Where isIrreversible() checks ToolContract.reversibility() of the target tool.
// Also checkpoint when a goal transitions to COMPLETED.
```

**Tests to add**
- `CheckpointTest#irreversibleAction_checkpointTakenBeforeDispatch`
- `CheckpointTest#goalCompleted_checkpointTaken`

---

#### FINDING-015

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.AgentRuntime` |
| **Method** | `replay(Snapshot, Agent, String, String)` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"State Serialization and Checkpointing" |

**Problem**  
`replay()` validates `integrityHash` but does not validate `schemaVersion` against a `CURRENT_SCHEMA_VERSION` constant. A snapshot from an older framework version with an incompatible state schema can be loaded silently, producing undefined runtime behavior.

**Evidence**  
`AgentRuntime.java` — integrity check confirmed present. No schema version check found.

**Fix**

```java
if (!snap.schemaVersion().equals(CURRENT_SCHEMA_VERSION))
    throw new IllegalArgumentException("Schema version mismatch: "
        + snap.schemaVersion() + " vs " + CURRENT_SCHEMA_VERSION);
```

**Test to add**
- `RuntimeTest#replay_schemaVersionMismatch_throwsIllegalArgument`

---

#### FINDING-016

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.DefaultExecutionContext` |
| **Method** | `checkpoint()` |
| **Severity** | `[MINOR]` |
| **Spec ref** | Vol. 1 §"Action Logging for Observability and Replay" |

**Problem**  
`checkpoint()` creates a `FullSnapshot` but emits no `CHECKPOINT_SAVED` event. Operators cannot observe checkpoint frequency or detect checkpoint failures through the event stream.

**Evidence**  
`DefaultExecutionContext.checkpoint()` — no `emit()` call. No `CHECKPOINT_SAVED` in `AgentEvent.EventType` usages found.

**Fix**  
Add `AgentEvent.EventType.CHECKPOINT_SAVED`. Emit from `DefaultExecutionContext.checkpoint()` passing the `runId`, `cycle`, and `snapshotHash` as attributes.

**Test to add**
- `CheckpointTest#checkpoint_emitsCheckpointSavedEvent`

---

### 1.7 — Tool Contract

**Check: Versioned tool identity** ✅  
`ToolContract.readOnly("fail", "1.0", "fail")` in tests confirms `version` is a constructor parameter.

#### FINDING-017

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.action.ToolException` |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Tool Calling — The Mechanism, the Protocol, the Contract" |

**Problem**  
`ToolException` takes two `String` arguments (code, message). The spec requires a typed error taxonomy — an enum of error codes the runtime can act on programmatically. Using raw strings means the runtime cannot distinguish `RATE_LIMITED` (should retry with backoff) from `NOT_FOUND` (should not retry) from `SCHEMA_VIOLATION` (should fail the plan) at compile time.

**Evidence**  
`ExtendedCoverageTest.java` — `new ToolException("ERR", "forced")`. No `ToolErrorCode` enum found.

**Fix**

```java
enum ToolErrorCode { NOT_FOUND, SCHEMA_VIOLATION, RATE_LIMITED,
                     PERMISSION_DENIED, TIMEOUT, INTERNAL_ERROR }
// Change to:
new ToolException(ToolErrorCode code, String message)
```

**Test to add**
- `ActionTest#toolException_rateLimit_retriesBeforeTerminating`

---

#### FINDING-018

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.action.ToolContract` |
| **Method** | N/A |
| **Severity** | `[MINOR]` |
| **Spec ref** | Vol. 1 §"Tool Calling" |

**Problem**  
Idempotency declaration as an explicit `boolean` field on `ToolContract` cannot be confirmed. The spec requires idempotency to be declared so the runtime can safely retry without duplicate side-effect risk.

**Evidence**  
`ToolContract` not directly read; no `isIdempotent()` usage found in any production code.

**Fix**  
Confirm or add `boolean isIdempotent()` to `ToolContract`. The `readOnly` factory should set `isIdempotent=true`; write factories should require explicit declaration.

**Test to add**
- `ToolContractTest#idempotentTool_retriedOnTimeout_noDuplicateEffect`

---

#### FINDING-019

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.action.ToolContract` |
| **Method** | N/A |
| **Severity** | `[MINOR]` |
| **Spec ref** | Vol. 1 §"Tool Calling" |

**Problem**  
No `dataFreshness` or `maxStalenessSeconds` field on `ToolContract`. The spec lists freshness profile as a required contract field. Without it, the agent cannot reason about whether cached tool results are still valid for the current reasoning step.

**Evidence**  
No freshness field found in any `ToolContract`-related code.

**Fix**  
Add `Duration maxStaleness()` to `ToolContract` (null = always fresh). `ContextWindowManager` or `Review` can then evict `WorkingMemoryEntry` instances whose origin tool result is older than `maxStaleness`.

**Test to add**
- `ToolContractTest#staleToolResult_exceededMaxStaleness_evictedFromWM`

---

### 1.8 — Delegation / Multi-Agent

**Check: Delegation depth limit** ✅  
`StateMachineRunner` `INITIALIZED` case guards `currentChainDepth() > maxChainDepth`.

**Check: Delegation cycle detection**  
No `visitedRunIds`, `ancestryChain`, or cycle-detection logic found anywhere.

#### FINDING-020

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.DefaultExecutionContext` / multi package |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Delegation to Subagents" |

**Problem**  
No delegation cycle detection exists. The spec explicitly warns: "AgentA delegates to AgentB, which delegates to AgentC, which calls AgentA again — are a serious production risk." With only a depth limit, a cycle at depth 1 (A→B→A) is caught only when depth exceeds the limit, not immediately.

**Fix**  
Add `Set<String> ancestorRunIds` to `ExecutionContext` (passed down during delegation). On each delegation, check if `targetAgentId` or `targetRunId` is already in `ancestorRunIds`. If so, terminate with `TerminationReason.ResourceLimit("Delegation cycle detected")`.

**Test to add**
- `MultiAgentTest#delegationCycle_detected_terminatesImmediately`

---

#### FINDING-021

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.foundation.DelegateToAgent` (inferred) |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Delegation to Subagents" |

**Problem**  
Cannot confirm delegation contract carries all 5 spec-required fields: task specification, context payload, timeout/budget, result contract (typed schema), and failure protocol. If any are absent, the parent agent cannot validate the subagent result or implement the failure protocol correctly.

**Fix**  
Read `DelegateToAgent.java`. Confirm all 5 fields present. Add `resultContract` as a typed schema reference. Add `failureProtocol` as a `FailureProtocol` enum `{ RETRY, FALLBACK, TERMINATE, ESCALATE }`.

**Test to add**
- `MultiAgentTest#delegation_subagentResultValidatedAgainstContract`

---

## PASS 2 — SOLID & Clean Code Audit

---

### 2.1 — Single Responsibility

#### FINDING-022

| Field | Value |
|---|---|
| **Pass** | 2 |
| **Class** | `com.agentframework.core.Review` |
| **Method** | `step(ActionResult, Decision, Observations, ExecutionContext, Agent)` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | SOLID — Single Responsibility Principle |

**Problem**  
`Review.step()` performs 8 distinct responsibilities in a single ~80-line method:
1. Taint classification
2. Working memory write
3. Context-window eviction
4. Goal stack update
5. Belief state update
6. Long-term memory write-back
7. Plan re-validation after world change
8. Termination check

Each is a separate reason to change.

**Fix**  
Extract to 7 collaborators:
- `TaintPolicy.classify(result)`
- `WorkingMemoryWriter.write()`
- `GoalCompletionPolicy.evaluate(result, decision, ctx)`
- `BeliefUpdater.update(result, ctx)`
- `MemoryWriteBack.write(result, agent, ctx)`
- `WorldChangeRevalidator.revalidate(result, ctx)`
- `TerminationEvaluator.evaluate(result, decision, ctx)`

`Review` becomes an orchestrator delegating to these 7 collaborators.

**Test to add**  
Each extracted class gets its own unit test.

---

#### FINDING-023

| Field | Value |
|---|---|
| **Pass** | 2 |
| **Class** | `com.agentframework.core.AgentRuntime` |
| **Method** | `execute()`, `executeAsync()`, `executeWith()`, `replay()` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | SOLID — Single Responsibility Principle |

**Problem**  
`AgentRuntime` is responsible for:
1. `ExecutionContext` creation
2. Synchronous run orchestration
3. Async thread pool management
4. Snapshot integrity validation (in `replay()`)
5. Event emission for `RUN_STARTED`/`COMPLETED`

These are 5 distinct responsibilities.

**Fix**  
Extract:
- `ExecutionContextFactory.create(task, tenant, user)`
- `RuntimeExecutor.executeSync(agent, ctx)`
- Injected `ExecutorService` for async
- `SnapshotVerifier` for integrity check

`AgentRuntime` becomes a thin facade.

**Test to add**
- `ExecutionContextFactoryTest#create_withDefaults_populatesAllFields`

---

### 2.2 — Open/Closed

#### FINDING-024

| Field | Value |
|---|---|
| **Pass** | 2 |
| **Class** | `com.agentframework.core.StateMachineRunner` |
| **Method** | `step(Agent, ExecutionContext)` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | SOLID — Open/Closed Principle |

**Problem**  
Adding a new `RunState` requires modifying `StateMachineRunner.step()`. The switch statement is closed to extension. Every new state or state variant (e.g., `AWAITING_RESULT`, `RESPONDING`) requires a code change and a re-test of all existing branches.

**Evidence**  
`StateMachineRunner.java` — `switch(ctx.currentState())` with 6 explicit cases plus `default`. `RESPONDING` and `AWAITING_RESULT` are present in `RunState` but not in the switch.

**Fix**

```java
interface StepHandler { void handle(Agent agent, ExecutionContext ctx); }
// Populate Map<RunState, StepHandler> handlers in constructor.
// step() becomes:
StepHandler h = handlers.get(ctx.currentState());
if (h == null) throw new IllegalStateException(...);
h.handle(agent, ctx);
```

New states are additions (new `StepHandler` impl + registration), not modifications.

**Test to add**
- `StateMachineRunnerTest#newState_withRegisteredHandler_dispatches`

---

**Check: CompositePlanValidator — OCP-compliant** ✅  
Adding a new validator is an addition (new impl + list entry), not a modification of `CompositePlanValidator`.

---

### 2.3 — Liskov Substitution

#### FINDING-025

| Field | Value |
|---|---|
| **Pass** | 2 |
| **Class** | `com.agentframework.core.PassThroughPlanValidator` (inferred) |
| **Method** | `validateAfterAction(ActionResult, ExecutionContext)` |
| **Severity** | `[BLOCKER]` |
| **Spec ref** | SOLID — Liskov Substitution Principle |

**Problem**  
`PassThroughPlanValidator` is used as the test double for `PlanValidator`. If its `validateAfterAction()` returns anything other than `Passed` consistently (e.g., throws `UnsupportedOperationException`, returns null, or has inconsistent behavior), then tests that rely on it produce false positives. The name "PassThrough" implies it must always return `Passed` for all methods.

**Evidence**  
`PassThroughPlanValidator` used in all integration tests as the default validator.

**Fix**  
Confirm both `validate()` and `validateAfterAction()` return `new ValidationResult.Passed()` with no conditions or side effects. Add a contract test.

**Test to add**
- `PassThroughPlanValidatorTest#allMethods_alwaysReturnPassed`

---

### 2.4 — Interface Segregation

#### FINDING-026

| Field | Value |
|---|---|
| **Pass** | 2 |
| **Class** | `com.agentframework.core.ExecutionContext` |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | SOLID — Interface Segregation Principle |

**Problem**  
`ExecutionContext` exposes all state accessors to all callers. `Agent.perception().perceive(ctx)` passes the full `ExecutionContext`. Perception could call `ctx.setTerminationReason()` — nothing prevents it.

**Fix**  
Define narrow view interfaces:

```java
PerceptionContext  { goalStack(), workingMemory(), task() }
ReasoningContext   { goalStack(), workingMemory(), beliefState(),
                    cycleCount(), totalTokensUsed() }
ActionContext      { goalStack(), workingMemory(), requestContext() }
```

Pass only the appropriate view to each layer.

**Test to add**
- `PerceptionTest#perceive_receivesOnlyPerceptionContext_notFullCtx`

---

#### FINDING-027

| Field | Value |
|---|---|
| **Pass** | 2 |
| **Class** | `com.agentframework.core.PlanValidator` |
| **Method** | `validate()` / `validateAfterAction()` |
| **Severity** | `[MINOR]` |
| **Spec ref** | SOLID — Interface Segregation Principle |

**Problem**  
`PlanValidator` combines pre-action validation (`validate()`) and post-action world-change re-validation (`validateAfterAction()`). These are different execution phases with different callers. Classes that only do one phase must implement a do-nothing stub for the other.

**Fix**  
Split into `PreActionValidator { validate() }` and `PostActionValidator { validateAfterAction() }`. `CompositePlanValidator` can implement both. `StateMachineRunner` depends on `PreActionValidator`, `Review` depends on `PostActionValidator`.

**Test to add**
- `PlanValidatorTest#preAndPost_areIndependentlyInjectable`

---

### 2.5 — Dependency Inversion

#### FINDING-028

| Field | Value |
|---|---|
| **Pass** | 2 |
| **Class** | `com.agentframework.core.StateMachineRunner` |
| **Method** | `StateMachineRunner(PlanValidator, EventSink) — default constructor` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | SOLID — Dependency Inversion Principle |

**Problem**  
The default constructor calls `new TaintClassifier()`. `TaintClassifier` is a concrete class. `StateMachineRunner` depends on the concrete implementation, not an abstraction. This prevents swapping the taint classification strategy without modifying `StateMachineRunner`.

**Evidence**

```java
// StateMachineRunner.java:
StateMachineRunner(PlanValidator v, EventSink e) {
    this(v, e, new DefaultLivenessDetector(), new TaintClassifier());
}
```

**Fix**  
Define `interface TaintPolicy { TaintLabel classify(Object result, ExecutionContext ctx); }`. Inject `TaintPolicy` into `StateMachineRunner` and `Review`. Remove `new TaintClassifier()` from all constructors.

**Test to add**
- `StateMachineRunnerTest#taintPolicy_injected_usedForClassification`
