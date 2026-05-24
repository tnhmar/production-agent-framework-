# Production-Grade Review Report — FULL

## Repository: `tnhmar/production-agent-framework-`
## Spec: Volume 1 — *Architecting AI Agent Systems*
## Report date: 2026-05-24
## Passes covered: 1 · 2 · 3 (partial) · 4 (partial) · 5 · 6

> **Coverage note**
> FINDING-001 through FINDING-028 (Pass 1–2) and FINDING-057 through FINDING-064 (Pass 5)
> are documented in full below.  
> FINDING-029 through FINDING-056 (Passes 3–4) were referenced in the Pass 6 backlog
> but their individual finding blocks were not included in the submitted report text.
> They are listed in the **[Pass 3–4 Placeholder](#pass-34--findings-placeholder-finding-029--finding-056)**
> section with their fix summaries as recorded in the Pass 6 backlog.

---

## Table of Contents

1. [Pass 1 — Specification Conformance Audit](#pass-1--specification-conformance-audit)
   - [1.1 State Machine](#11--state-machine)
   - [1.2 Goal Stack](#12--goal-stack)
   - [1.3 Belief State](#13--belief-state)
   - [1.4 Action Safety](#14--action-safety)
   - [1.5 Working Memory & Context Window Management](#15--working-memory--context-window-management)
   - [1.6 Checkpointing](#16--checkpointing)
   - [1.7 Tool Contract](#17--tool-contract)
   - [1.8 Delegation / Multi-Agent](#18--delegation--multi-agent)
2. [Pass 2 — SOLID & Clean Code Audit](#pass-2--solid--clean-code-audit)
   - [2.1 Single Responsibility](#21--single-responsibility)
   - [2.2 Open/Closed](#22--openclosed)
   - [2.3 Liskov Substitution](#23--liskov-substitution)
   - [2.4 Interface Segregation](#24--interface-segregation)
   - [2.5 Dependency Inversion](#25--dependency-inversion)
3. [Pass 3–4 — Placeholder (FINDING-029 to FINDING-056)](#pass-34--findings-placeholder-finding-029--finding-056)
4. [Pass 5 — Observability & Security Audit](#pass-5--observability--security-audit)
   - [5.1 Observability gaps](#51--observability-gaps)
   - [5.2 Security gaps](#52--security-gaps)
5. [Pass 6 — Prioritised Fix Backlog](#pass-6--prioritised-fix-backlog)
6. [Finding Summary](#finding-summary)
7. [New Test Classes Required](#new-test-classes-required)

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
The switch `default` branch is `default -> {}` — a silent no-op. Any state added to `RunState` without a corresponding handler produces an infinite live loop with no diagnostic.

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
**Check: GoalStatus has DEFERRED** ✅

#### FINDING-004

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.GoalStatus` / `GoalStack` |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Goal Stack and Subgoal Tracking" |

**Problem**  
`GoalStatus.DEFERRED` exists as an enum constant but carries no `resumeCondition`. Without this, `DEFERRED` is semantically identical to `FAILED` — the goal is dropped, not paused.

**Evidence**  
`GoalStatus.java` — plain enum, no fields. No `defer(id, condition)` method found in `GoalStack`.

**Fix**  
(a) Convert `GoalStatus` to a sealed interface with a `Deferred(String resumeCondition)` record, or  
(b) Add `Map<String,String> deferredConditions` to `GoalStack` with `defer(goalId, resumeCondition)` / `tryResume(ctx)` methods.

**Test to add**
- `GoalStackTest#defer_withCondition_resumesWhenConditionHolds`

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
No intent-anchoring check exists. Without this, an agent can pursue a locally coherent subgoal that has drifted from the original user intent for an unbounded number of cycles.

**Fix**  
Add `LivenessDetector.checkIntentAlignment(topLevelGoal, activeGoal, ctx)` called once per `PLANNING` cycle. Return `Optional<TerminationReason>`. Emit `GOAL_INTENT_DRIFT` event when triggered.

**Test to add**
- `LivenessDetectorTest#intentAlignment_driftedSubgoal_returnsReason`

---

### 1.3 — Belief State

**Check: Provenance fields** ✅  
**Check: Confidence annotation** ✅  
**Check: Conflict detection — silent overwrite** ✅  
**Check: Separate BeliefState / WorkingMemory types** ✅

#### FINDING-006

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.DefaultBeliefState` |
| **Method** | `assertBelief(Belief)` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Belief State" |

**Problem**  
`BELIEF_CONFLICT` event emission is not self-contained in `DefaultBeliefState`. It is handled only by `Review.updateBeliefs()`. Any caller that calls `assertBelief()` directly will silently miss conflict events.

**Evidence**  
`DefaultBeliefState.java` — no `EventSink` field, no `emit()` call.

**Fix**  
Inject `EventSink` into `DefaultBeliefState` constructor. Emit `BELIEF_CONFLICT` from within `assertBelief()`. Remove duplicate emission from `Review.updateBeliefs()`.

**Test to add**
- `BeliefStateTest#assertBelief_conflict_emitsBeliefConflictEvent`

---

### 1.4 — Action Safety

#### FINDING-007

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.action.ToolContract` |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"The Action Layer — Tool Calling" |

**Problem**  
The `Reversibility` classification cannot be confirmed as a typed four-value enum. Only two factory methods visible: `readOnly()` and `write()`.

**Fix**  
Confirm `enum Reversibility { READ_ONLY, IDEMPOTENT_WRITE, NON_IDEMPOTENT_WRITE, IRREVERSIBLE }`. Gate `IRREVERSIBLE` tools with `ctx.checkpoint()` before dispatch.

**Test to add**
- `ToolContractTest#reversibilityEnum_allFourValuesPresent`

---

#### FINDING-008

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.CompositePlanValidator` |
| **Method** | `validate(Decision, ExecutionContext)` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Action Validation" |

**Problem**  
The composite pipeline exists but the four spec-required layers (schema, semantic, policy, safety) are not typed. An operator cannot configure or disable individual layers by type.

**Fix**  
Define `enum ValidationLayer { SCHEMA, SEMANTIC, POLICY, SAFETY }`. Add `ValidationLayer layer()` to `PlanValidator`. `CompositePlanValidator` enforces all four are present and ordered.

**Test to add**
- `CompositePlanValidatorTest#allFourLayers_presentAndOrderedCorrectly`

---

#### FINDING-009

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.ValidationResult` |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Action Validation" |

**Problem**  
`ValidationResult` sealed interface has no `RequiresApproval` variant. Without it, there is no "pause and wait for approval" path — irreversible actions either pass silently or terminate the agent.

**Evidence**  
`ValidationResult.java` — sealed permits `Passed`, `Failed`, `NeedsCorrection` only.

**Fix**

```java
record RequiresApproval(String reason, String approvalToken)
    implements ValidationResult {}
```

Update `StateMachineRunner` to route `RequiresApproval` → `SUSPENDED_HITL`.

**Test to add**
- `PlanValidatorTest#requiresApproval_pausesRun_notTerminates`

---

### 1.5 — Working Memory & Context Window Management

#### FINDING-010

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core` (WorkingMemoryTier) |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Working Memory" and §"Context Window Management" |

**Problem**  
Only `WorkingMemoryTier.ACTIVE` is used anywhere in production code. `SUMMARIZED` and `COMPRESSED` tiers are absent or dead. `ContextWindowManager.manage()` calls `wm.evictLowestRelevance(half)` which hard-deletes entries instead of demoting them.

**Fix**
1. Confirm `WorkingMemoryTier` has `ACTIVE`, `SUMMARIZED`, `COMPRESSED`.
2. Replace evict with demote: `ACTIVE→SUMMARIZED` compresses content; `SUMMARIZED→COMPRESSED` replaces with a reference token.
3. `ContextWindowManager.manage()` calls `wm.demote(entry)` not `wm.evict(entry)`.

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
`ContextWindowManager.manage()` is a 4-line naive stub that hard-deletes the bottom half of working memory. No `prune()`, no `summarize()`, no tier demotion, no configurable threshold, no token-size events.

**Evidence**

```java
if (wm.estimatedTokenCount() > (int)(0.70 * maxTokens))
    wm.evictLowestRelevance(half);
```

**Fix**  
Full rewrite:
- Injectable `Summarizer` interface
- `prune(wm, threshold)` returning demoted entries
- `promote(entry)` for re-activation
- Emit `CONTEXT_WINDOW_PRESSURE` and `CONTEXT_WINDOW_EVICTION` events
- `EVICTION_THRESHOLD` as constructor parameter (default 0.70)

**Tests to add**
- `ContextWindowTest#manage_aboveThreshold_emitsContextWindowPressure`
- `ContextWindowTest#manage_demotesEntries_insteadOfDeleting`

---

#### FINDING-012

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.StateMachineRunner` / `Review` |
| **Method** | `step()` |
| **Severity** | `[MINOR]` |
| **Spec ref** | Vol. 1 §"Working Memory" |

**Problem**  
Working memory token size is not instrumented per cycle. `CYCLE_STARTED` emits cycle count only — no `wmTokens` attribute.

**Fix**  
Emit `"wmTokens" → ctx.workingMemory().estimatedTokenCount()` in `CYCLE_STARTED`.

**Test to add**
- `ObservabilityExtendedTest#cycleStarted_includesWorkingMemoryTokenCount`

---

#### FINDING-013

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.perception.SimplePerception` |
| **Method** | `perceive(ExecutionContext)` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Context Window Management — Filling" |

**Problem**  
Cannot confirm `SimplePerception.perceive()` filters by the current active subgoal. An exhaustive perceive() wastes context tokens and violates the "desk space" allocation model.

**Fix**  
Confirm goal-scoped filtering against `ctx.goalStack().current()`. If absent, inject a `RelevanceScorer` and filter to top-K.

**Test to add**
- `PerceptionTest#perceive_filtersToActiveGoalRelevance_notExhaustive`

---

### 1.6 — Checkpointing

#### FINDING-014

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.StateMachineRunner` / `DefaultAction` |
| **Method** | `step() PLANNING case` / `execute(Decision, ExecutionContext)` |
| **Severity** | `[BLOCKER]` |
| **Spec ref** | Vol. 1 §"State Serialization and Checkpointing" |

**Problem**  
No checkpoint is taken before executing any action, irreversible or otherwise. A failed irreversible action leaves the agent in a state that cannot be replayed to the pre-action point.

**Fix**

```java
if (isIrreversible(decision)) { ctx.checkpoint(); }
```

Also checkpoint when a goal transitions to `COMPLETED`.

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
`replay()` validates `integrityHash` but not `schemaVersion`. An older-schema snapshot can be loaded silently.

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
`checkpoint()` creates a `FullSnapshot` but emits no `CHECKPOINT_SAVED` event.

**Fix**  
Add `AgentEvent.EventType.CHECKPOINT_SAVED`. Emit from `checkpoint()` with `runId`, `cycle`, `snapshotHash`.

**Test to add**
- `CheckpointTest#checkpoint_emitsCheckpointSavedEvent`

---

### 1.7 — Tool Contract

**Check: Versioned tool identity** ✅

#### FINDING-017

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.action.ToolException` |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Tool Calling — The Mechanism, the Protocol, the Contract" |

**Problem**  
`ToolException` takes two raw `String` args. The runtime cannot distinguish `RATE_LIMITED` from `NOT_FOUND` from `SCHEMA_VIOLATION` at compile time.

**Fix**

```java
enum ToolErrorCode { NOT_FOUND, SCHEMA_VIOLATION, RATE_LIMITED,
                     PERMISSION_DENIED, TIMEOUT, INTERNAL_ERROR }
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
Idempotency declaration as an explicit `boolean isIdempotent()` on `ToolContract` cannot be confirmed.

**Fix**  
Confirm or add `boolean isIdempotent()`. `readOnly` factory sets `isIdempotent=true`; write factories require explicit declaration.

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
No `dataFreshness` / `maxStaleness` field on `ToolContract`. The agent cannot reason about whether cached tool results are still valid.

**Fix**  
Add `Duration maxStaleness()` to `ToolContract` (null = always fresh).

**Test to add**
- `ToolContractTest#staleToolResult_exceededMaxStaleness_evictedFromWM`

---

### 1.8 — Delegation / Multi-Agent

**Check: Delegation depth limit** ✅

#### FINDING-020

| Field | Value |
|---|---|
| **Pass** | 1 |
| **Class** | `com.agentframework.core.DefaultExecutionContext` / multi package |
| **Method** | N/A |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Delegation to Subagents" |

**Problem**  
No delegation cycle detection exists. With only a depth limit, a cycle at depth 1 (A→B→A) is caught only when depth exceeds the limit, not immediately.

**Fix**  
Add `Set<String> ancestorRunIds` to `ExecutionContext`. On each delegation, check for re-entry; terminate with `ResourceLimit("Delegation cycle detected")`.

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
Cannot confirm all 5 spec-required delegation contract fields: task spec, context payload, timeout/budget, result contract, failure protocol.

**Fix**  
Confirm all 5 fields. Add `failureProtocol` as `FailureProtocol` enum `{ RETRY, FALLBACK, TERMINATE, ESCALATE }`.

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
`Review.step()` performs 8 distinct responsibilities: (1) taint classification, (2) working memory write, (3) context-window eviction, (4) goal stack update, (5) belief state update, (6) long-term memory write-back, (7) plan re-validation after world change, (8) termination check.

**Fix**  
Extract to 7 collaborators: `TaintPolicy`, `WorkingMemoryWriter`, `GoalCompletionPolicy`, `BeliefUpdater`, `MemoryWriteBack`, `WorldChangeRevalidator`, `TerminationEvaluator`. `Review` becomes an orchestrator.

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
`AgentRuntime` has 5 distinct responsibilities: context creation, sync orchestration, async thread pool, snapshot integrity validation, event emission.

**Fix**  
Extract `ExecutionContextFactory`, `RuntimeExecutor`, `SnapshotVerifier`. `AgentRuntime` becomes a thin facade.

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
Adding a new `RunState` requires modifying `StateMachineRunner.step()`. The switch is closed to extension.

**Fix**

```java
interface StepHandler { void handle(Agent agent, ExecutionContext ctx); }
Map<RunState, StepHandler> handlers; // populated in constructor
// step():
StepHandler h = handlers.get(ctx.currentState());
if (h == null) throw new IllegalStateException(...);
h.handle(agent, ctx);
```

**Test to add**
- `StateMachineRunnerTest#newState_withRegisteredHandler_dispatches`

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
`PassThroughPlanValidator` is the test double for `PlanValidator` used in all integration tests. If `validateAfterAction()` does not consistently return `Passed`, tests produce false positives.

**Fix**  
Confirm both `validate()` and `validateAfterAction()` always return `new ValidationResult.Passed()`. Add a contract test.

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
`ExecutionContext` exposes all state accessors to all callers. `Agent.perception().perceive(ctx)` could call `ctx.setTerminationReason()` — nothing prevents it.

**Fix**  
```java
PerceptionContext  { goalStack(), workingMemory(), task() }
ReasoningContext   { goalStack(), workingMemory(), beliefState(), cycleCount(), totalTokensUsed() }
ActionContext      { goalStack(), workingMemory(), requestContext() }
```

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
`PlanValidator` combines pre-action and post-action phases in one interface, forcing no-op stub implementations.

**Fix**  
Split into `PreActionValidator { validate() }` and `PostActionValidator { validateAfterAction() }`.

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
Default constructor calls `new TaintClassifier()` — depends on a concrete class, not an abstraction.

**Evidence**

```java
StateMachineRunner(PlanValidator v, EventSink e) {
    this(v, e, new DefaultLivenessDetector(), new TaintClassifier());
}
```

**Fix**  
Define `interface TaintPolicy { TaintLabel classify(Object result, ExecutionContext ctx); }`. Inject into `StateMachineRunner` and `Review`. Remove all `new TaintClassifier()` wiring.

**Test to add**
- `StateMachineRunnerTest#taintPolicy_injected_usedForClassification`

---

## PASS 3–4 — Findings Placeholder (FINDING-029 – FINDING-056)

> ⚠️ **Note:** The full finding blocks for FINDING-029 through FINDING-056 were not included
> in the submitted report text. The entries below reproduce the fix summaries exactly as
> recorded in the Pass 6 Prioritised Fix Backlog. Full Problem / Evidence / Test blocks
> should be added when the Pass 3–4 report text becomes available.

### Passes 3–4 — MAJOR findings (referenced in backlog)

| Finding | Class | Fix summary |
|---------|-------|-------------|
| FINDING-029 | `Review` | Remove inline `new ContextWindowManager()`; inject via `StateMachineRunner` constructor |
| FINDING-030 | `StateMachineRunner` | Construct `Review` in constructor; store as `private final Review review` |
| FINDING-031 | `StateMachineRunner` / `Review` | Add `Task.maxRevisions()` (default 3); replace all `isRevisionBudgetExceeded(3)` literals |
| FINDING-033 | `Review` | Add `Task.maxConsecutiveFailures()` (default 3); replace `MAX_FAILURES` literal |
| FINDING-036 | `LLMReasoning` | Wrap `LLMProvider` call in try/catch; classify exception types to typed `TerminationReason` or retry |
| FINDING-037 | `AgentRuntime` | Add `exceptionally()` handler to `executeAsync()`; return structured `ExecutionResult` on unchecked exception |
| FINDING-038 | `DefaultExecutionContext` | Confirm all mutable counters are `AtomicInteger`; fix any plain `int` fields |
| FINDING-040 | `AgentRuntime` | Implement `AutoCloseable`; `close()` shuts down injected `ExecutorService` |
| FINDING-042 | *(BeliefState — detail pending)* | *(Full finding block not provided)* |
| FINDING-043 | *(Checkpoint — detail pending)* | *(Full finding block not provided)* |
| FINDING-044 | *(ContextWindow — detail pending)* | *(Full finding block not provided)* |
| FINDING-045 | *(LivenessDetector — detail pending)* | *(Full finding block not provided)* |

### Passes 3–4 — MINOR findings (referenced in backlog)

| Finding | Class | Fix summary |
|---------|-------|-------------|
| FINDING-032 | `ContextWindowManager` | Make `EVICTION_THRESHOLD` a constructor parameter with default 0.70 |
| FINDING-034 | `StateMachineRunner` | Extract `DEFAULT_WALL_CLOCK` as a named constant |
| FINDING-035 | `Review` | Extract `SUMMARY_MAX_CHARS = 200` as a named, configurable constant |
| FINDING-039 | `GoalStack` | Back with `ConcurrentLinkedDeque`; add `snapshot()` returning unmodifiable copy |
| FINDING-041 | `StateMachineRunner` | Remove `Long.MAX_VALUE` sentinel; pass `null` to `Budget` for no-limit |
| FINDING-046–048 | *(detail pending)* | *(Full finding blocks not provided)* |
| FINDING-049 | All test files | Rename all test methods to `<scenario>_<condition>_<expectedOutcome>`; add Vol. 1 spec references |
| FINDING-050 | *(detail pending)* | *(Full finding block not provided)* |
| FINDING-051 | `SecurityTest` | Add taint-preservation assertions for HOSTILE results *(BLOCKER)* |
| FINDING-052 | *(detail pending)* | *(Full finding block not provided)* |
| FINDING-053 | `MultiAgentCoverageTest` | Add depth+limit message assertions to delegation depth test |
| FINDING-054 | Multiple test files | Add behavioural assertions alongside all `assertDoesNotThrow` calls |
| FINDING-055 | Multiple test files | Audit for static/shared mutable state; move to `@BeforeEach` |
| FINDING-056 | `AgentEvent.EventType` | Add `CHECKPOINT_SAVED`, `REPLAY_STARTED`, `GOAL_COMPLETED`, `GOAL_FAILED` event types |

---

## PASS 5 — Observability & Security Audit

---

### 5.1 — Observability gaps

#### FINDING-057 (continued from prior pass)

| Field | Value |
|---|---|
| **Pass** | 5 |
| **Class** | `com.agentframework.core.DefaultAction` |
| **Method** | `execute()` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Action Logging for Observability and Replay" |

**Problem**  
Confirm `DefaultAction.execute()` emits `TOOL_CALL_DISPATCHED` before dispatch and `TOOL_CALL_COMPLETED` after, with structured attributes `{ "runId", "stepNumber", "toolName", "toolVersion", "inputHash", "dispatchedAt", "completedAt", "status", "retryCount" }`. Input parameters must be sanitized of credentials before emission.

**Tests to add**
- `ActionTest#toolDispatch_emitsToolCallDispatchedAndCompleted`
- `ActionTest#toolDispatch_inputCredentials_sanitizedInEvent`

---

#### FINDING-058

| Field | Value |
|---|---|
| **Pass** | 5 |
| **Class** | `com.agentframework.core.AgentRuntime` |
| **Method** | `executeWith(Agent, ExecutionContext)` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Action Logging for Observability and Replay" |

**Problem**  
`RUN_COMPLETED` event emission cannot be confirmed. `RUN_STARTED` is confirmed emitted. If `RUN_COMPLETED` is not emitted at the end of every run regardless of terminal state, operators have no reliable signal for alerting and SLA tracking.

**Evidence**  
`ExtendedCoverageTest#15` checks `RUN_STARTED`. No test checks `RUN_COMPLETED`.

**Fix**  
Emit `RUN_COMPLETED` unconditionally after `StateMachineRunner.run()` returns — including on exception — with attributes `{ "finalState", "terminationReason", "totalCycles", "totalTokens", "durationMs" }`.

**Test to add**
- `RuntimeTest#execute_alwaysEmitsRunCompleted_regardlessOfTerminalState`

---

#### FINDING-059

| Field | Value |
|---|---|
| **Pass** | 5 |
| **Class** | `com.agentframework.core.Review` |
| **Method** | `step() — updateBeliefs()` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Belief State" / §"Observability" |

**Problem**  
`BELIEF_CONFLICT` event subject is only `won.subject() + "|" + won.predicate()` — old/new values, confidence delta, and provenance are absent. An operator cannot reconstruct the conflict without querying the conflict log separately.

**Evidence**

```java
Map.of("key", won.subject() + "|" + won.predicate())
```

**Fix**

```java
Map.of("subject", subject, "predicate", predicate,
       "oldValue", existing.object(), "newValue", incoming.object(),
       "oldConfidence", existing.confidence(),
       "newConfidence", incoming.confidence(),
       "winnerId", won.beliefId())
```

**Test to add**
- `BeliefStateTest#beliefConflictEvent_containsFullConflictContext`

---

#### FINDING-060

| Field | Value |
|---|---|
| **Pass** | 5 |
| **Class** | `com.agentframework.core.AgentRuntime` / `DefaultExecutionContext` |
| **Method** | `replay()` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Action Logging for Observability and Replay" |

**Problem**  
No `REPLAY_STARTED` event is emitted when a replay begins. Operators cannot correlate replay executions with the original run ID in the event stream.

**Evidence**  
No `REPLAY_STARTED` in `AgentEvent.EventType` usage found anywhere.

**Fix**

```java
{ "originalRunId": snap.runId(), "replayedAt": Instant.now(),
  "replayTenant": tenantId, "snapshotCycle": snap.cycle() }
```

**Test to add**
- `RuntimeTest#replay_emitsReplayStartedEvent_withOriginalRunId`

---

#### FINDING-061

| Field | Value |
|---|---|
| **Pass** | 5 |
| **Class** | `com.agentframework.core.StateMachineRunner` |
| **Method** | `step() — PLANNING case` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Action Logging for Observability and Replay" |

**Problem**  
`GOAL_COMPLETED` event is not confirmed as emitted when a goal transitions to `COMPLETED`. `Review.updateGoals()` calls `updateStatus()` with no visible `emit()`. Operators cannot track goal lifecycle in the event stream.

**Fix**  
(a) `GoalStack.updateStatus()` emits `GOAL_COMPLETED` / `GOAL_FAILED` via an injected `EventSink` (preferred), or  
(b) `Review.updateGoals()` emits after calling `updateStatus()`.

**Tests to add**
- `CoreTest#goalCompleted_emitsGoalCompletedEvent`
- `CoreTest#goalFailed_emitsGoalFailedEvent`

---

### 5.2 — Security gaps

#### FINDING-062

| Field | Value |
|---|---|
| **Pass** | 5 |
| **Class** | `com.agentframework.core.Review` |
| **Method** | `step() — working memory write after taint classification` |
| **Severity** | `[BLOCKER]` |
| **Spec ref** | Vol. 1 §"Security — Taint Propagation" |

**Problem**  
When `taint == HOSTILE`, the entry is **still written to working memory**. Taint detection is observability-only — hostile content is NOT quarantined from further LLM reasoning.

**Evidence**

```java
TaintLabel taint = classifyResultTaint(result);  // may be HOSTILE
ctx.workingMemory().add(new WorkingMemoryEntry(..., taint));  // always added
if (taint == TaintLabel.HOSTILE) { events.emit(...) }  // event only — no quarantine
```

**Fix**  
When `taint == HOSTILE`:
1. Do **NOT** add the full entry to working memory.
2. Add a sanitized placeholder: `"Tool result quarantined: hostile content detected [eventId]"` with `TaintLabel.HOSTILE`.
3. Emit `HOSTILE_TAINT_DETECTED` event.
4. `ctx.flagPlanStale("Tool result quarantined — hostile content")`.

**Tests to add**
- `SecurityTest#hostileTaintedResult_quarantined_notAddedToWorkingMemory`
- `SecurityTest#hostileTaintedResult_placeholderAdded_withHostileLabel`
- `SecurityTest#hostileTaintedResult_flagsPlanStale`

---

#### FINDING-063

| Field | Value |
|---|---|
| **Pass** | 5 |
| **Class** | `com.agentframework.core.Review` |
| **Method** | `updateBeliefs(ActionResult, ExecutionContext)` |
| **Severity** | `[BLOCKER]` |
| **Spec ref** | Vol. 1 §"Security — Taint Propagation" |

**Problem**  
A `HOSTILE`-tainted tool result can be stored as a belief with no taint annotation. Future reasoning sees clean-looking structured data that originated from a prompt injection.

**Evidence**

```java
Belief incoming = new Belief(UUID.randomUUID().toString(),
    "last_tool_result", "equals", value, 0.8,
    "tool_result", Instant.now(), false);
// No taint label on the Belief record.
```

**Fix**
1. Confirm `Belief` record has a `TaintLabel` field (add if absent).
2. Pass the result's classified taint to the `Belief` constructor in `updateBeliefs()`.
3. In `DefaultBeliefState.assertBelief()`, reject `HOSTILE` beliefs and log with a `TAINT_REJECTED` marker.

**Tests to add**
- `SecurityTest#hostileBelief_assertBelief_rejectedNotStored`
- `SecurityTest#belief_taintLabel_propagatedFromToolResult`

---

#### FINDING-064

| Field | Value |
|---|---|
| **Pass** | 5 |
| **Class** | `com.agentframework.core.AgentRuntime` |
| **Method** | `replay() — integrity hash comparison` |
| **Severity** | `[MINOR]` |
| **Spec ref** | Vol. 1 §"Security" / Pass 5 |

**Problem**  
Integrity hash comparison likely uses `String.equals()`, which short-circuits on first mismatch. A timing oracle on the replay endpoint could allow brute-forcing a valid hash.

**Fix**  
Replace with constant-time comparison:

```java
MessageDigest.isEqual(hash1.getBytes(UTF_8), hash2.getBytes(UTF_8))
```

**Test to add**
- `AgentRuntimeTest#replay_integrityCheck_usesConstantTimeComparison`

---

## PASS 6 — Prioritised Fix Backlog

---

### 🔴 BLOCKERS — Fix before any other work (8 items)

| # | Finding | Class | Fix summary | Effort |
|---|---------|-------|-------------|--------|
| B-1 | FINDING-003 | `StateMachineRunner` | Replace `default -> {}` with `throw new IllegalStateException(...)` | 15 min |
| B-2 | FINDING-011 | `ContextWindowManager` | Full rewrite: injectable `Summarizer`, tier demotion, `prune()`, `summarize()`, configurable threshold, events | 2 days |
| B-3 | FINDING-014 | `StateMachineRunner` / `DefaultAction` | `ctx.checkpoint()` before every irreversible action dispatch; checkpoint on goal completion | 4 h |
| B-4 | FINDING-025 | `PassThroughPlanValidator` | Confirm both `validate()` and `validateAfterAction()` always return `Passed`; add contract test | 1 h |
| B-5 | FINDING-036 | `LLMReasoning` | Wrap `LLMProvider` call in try/catch; classify exception types to typed `TerminationReason` or retry | 4 h |
| B-6 | FINDING-051 | `SecurityTest` | Add taint-preservation assertions for HOSTILE results | 2 h |
| B-7 | FINDING-062 | `Review` | Quarantine HOSTILE-tainted entries — do NOT write raw content to working memory | 4 h |
| B-8 | FINDING-063 | `Review` / `Belief` | Add `TaintLabel` to `Belief` record; reject HOSTILE beliefs in `assertBelief()` | 4 h |

---

### 🟡 MAJORS — Fix in Sprint 2 (26 items)

| # | Finding | Class | Fix summary | Effort |
|---|---------|-------|-------------|--------|
| M-1 | FINDING-001 | `StateMachineRunner` | Add `RESPONDING`, `AWAITING_RESULT` handlers (or throw) | 2 h |
| M-2 | FINDING-002 | `StateMachineRunner` | `DEGRADED` → `ABORTED` not `TERMINATED`; make `ABORTED` reachable | 1 h |
| M-3 | FINDING-004 | `GoalStatus` / `GoalStack` | Add `resumeCondition` to `DEFERRED`; add `defer(id, condition)` / `tryResume(ctx)` | 1 day |
| M-4 | FINDING-005 | `DefaultLivenessDetector` | Add `checkIntentAlignment(topGoal, activeGoal, ctx)` returning `Optional<TerminationReason>` | 1 day |
| M-5 | FINDING-006 | `DefaultBeliefState` | Inject `EventSink`; emit `BELIEF_CONFLICT` from `assertBelief()` directly | 3 h |
| M-6 | FINDING-007 | `ToolContract` | Confirm / add 4-value `Reversibility` enum | 4 h |
| M-7 | FINDING-008 | `CompositePlanValidator` | Add `ValidationLayer` enum; type each validator layer; enforce ordering and completeness | 1 day |
| M-8 | FINDING-009 | `ValidationResult` | Add `RequiresApproval(reason, approvalToken)` sealed variant; route to `SUSPENDED_HITL` | 1 day |
| M-9 | FINDING-010 | `WorkingMemoryTier` / `ContextWindowManager` | Confirm `SUMMARIZED`, `COMPRESSED` tiers; replace hard-delete with tier demotion | 1 day |
| M-10 | FINDING-013 | `SimplePerception` | Add goal-scoped relevance filtering; inject `RelevanceScorer` | 1 day |
| M-11 | FINDING-015 | `AgentRuntime` | Add `CURRENT_SCHEMA_VERSION` constant; validate on `replay()` | 2 h |
| M-12 | FINDING-017 | `ToolException` | Add `ToolErrorCode` enum; change `ToolException(ToolErrorCode, String)` | 4 h |
| M-13 | FINDING-020 | `DefaultExecutionContext` | Add `ancestorRunIds` set; cycle detection on delegation | 4 h |
| M-14 | FINDING-021 | `DelegateToAgent` | Confirm/add all 5 delegation contract fields; add typed `FailureProtocol` enum | 4 h |
| M-15 | FINDING-022 | `Review` | Extract 7 collaborators | 2 days |
| M-16 | FINDING-023 | `AgentRuntime` | Extract `ExecutionContextFactory` and `SnapshotVerifier` | 1 day |
| M-17 | FINDING-024 | `StateMachineRunner` | Replace switch with `Map<RunState, StepHandler>` dispatch table | 1 day |
| M-18 | FINDING-026 | `ExecutionContext` | Define narrow view interfaces: `PerceptionContext`, `ReasoningContext`, `ActionContext` | 1 day |
| M-19 | FINDING-028 | `StateMachineRunner` | Define `TaintPolicy` interface; inject instead of `new TaintClassifier()` | 3 h |
| M-20 | FINDING-029 | `Review` | Remove inline `new ContextWindowManager()`; inject via `StateMachineRunner` constructor | 2 h |
| M-21 | FINDING-030 | `StateMachineRunner` | Construct `Review` in constructor; store as `private final Review review` | 1 h |
| M-22 | FINDING-031 | `StateMachineRunner` / `Review` | Add `Task.maxRevisions()` (default 3); replace `isRevisionBudgetExceeded(3)` literals | 2 h |
| M-23 | FINDING-033 | `Review` | Add `Task.maxConsecutiveFailures()` (default 3); replace `MAX_FAILURES` literal | 2 h |
| M-24 | FINDING-037 | `AgentRuntime` | Add `exceptionally()` handler to `executeAsync()` | 3 h |
| M-25 | FINDING-038 | `DefaultExecutionContext` | Confirm all mutable counters are `AtomicInteger` | 3 h |
| M-26 | FINDING-040 | `AgentRuntime` | Implement `AutoCloseable`; `close()` shuts down `ExecutorService` | 1 h |

---

### 🟢 MINORS — Fix in Sprint 3 (18 items)

| # | Finding | Class | Fix summary | Effort |
|---|---------|-------|-------------|--------|
| m-1 | FINDING-012 | `StateMachineRunner` | Emit `wmTokens` attribute in `CYCLE_STARTED` event | 1 h |
| m-2 | FINDING-016 | `DefaultExecutionContext` | Add `CHECKPOINT_SAVED` to `EventType`; emit from `checkpoint()` | 1 h |
| m-3 | FINDING-018 | `ToolContract` | Confirm/add explicit `boolean isIdempotent()` field | 1 h |
| m-4 | FINDING-019 | `ToolContract` | Add `Duration maxStaleness()` field | 2 h |
| m-5 | FINDING-027 | `PlanValidator` | Split into `PreActionValidator` and `PostActionValidator` | 3 h |
| m-6 | FINDING-032 | `ContextWindowManager` | Make `EVICTION_THRESHOLD` a constructor parameter with default 0.70 | 30 min |
| m-7 | FINDING-034 | `StateMachineRunner` | Extract `DEFAULT_WALL_CLOCK` as a named constant | 15 min |
| m-8 | FINDING-035 | `Review` | Extract `SUMMARY_MAX_CHARS = 200` as a named, configurable constant | 30 min |
| m-9 | FINDING-039 | `GoalStack` | Back with `ConcurrentLinkedDeque`; add `snapshot()` returning unmodifiable copy | 2 h |
| m-10 | FINDING-041 | `StateMachineRunner` | Remove `Long.MAX_VALUE` sentinel; pass `null` to `Budget` for no-limit | 30 min |
| m-11 | FINDING-049 | All test files | Rename test methods to `<scenario>_<condition>_<expectedOutcome>`; add spec references | 1 day |
| m-12 | FINDING-053 | `MultiAgentCoverageTest` | Add depth+limit message assertions to delegation depth test | 30 min |
| m-13 | FINDING-054 | Multiple test files | Add behavioural assertions alongside all `assertDoesNotThrow` calls | 2 h |
| m-14 | FINDING-055 | Multiple test files | Audit for static/shared mutable state; move to `@BeforeEach` | 2 h |
| m-15 | FINDING-056 | `AgentEvent.EventType` | Add `CHECKPOINT_SAVED`, `REPLAY_STARTED`, `GOAL_COMPLETED`, `GOAL_FAILED` event types | 1 h |
| m-16 | FINDING-059 | `Review` | Enrich `BELIEF_CONFLICT` event with full old/new values and provenance | 1 h |
| m-17 | FINDING-060 | `AgentRuntime` | Add `REPLAY_STARTED` emission in `replay()` with `originalRunId` attribute | 1 h |
| m-18 | FINDING-064 | `AgentRuntime` | Replace `String.equals()` hash comparison with `MessageDigest.isEqual()` | 30 min |

---

## Finding Summary

| Severity | Count | Must resolve before |
|---|---|---|
| **[BLOCKER]** | **8** | Any other sprint work |
| **[MAJOR]** | **35** | Production deployment |
| **[MINOR]** | **18** | First stable release |
| **Total** | **61** | — |

---

## New Test Classes Required

| Class | Covers |
|---|---|
| `BeliefStateTest` | FINDING-006, 042, 059, 063 |
| `CheckpointTest` | FINDING-014, 016, 043, 056 |
| `ContextWindowTest` | FINDING-010, 011, 044 |
| `LivenessDetectorTest` | FINDING-005, 045 |
| `StateMachineRunnerTest` | FINDING-001, 002, 003, 024, 028, 030 |
| `ToolContractTest` | FINDING-007, 017, 018, 019 |
| `AgentRuntimeTest` | FINDING-037, 040, 058, 060, 064 |
| `DefaultExecutionContextTest` | FINDING-038 |
| `GoalStackTest` | FINDING-004, 039 |
| `ReviewTest` | FINDING-022, 029, 033, 035 |
