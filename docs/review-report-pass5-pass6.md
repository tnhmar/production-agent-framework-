# Code Review Report — Pass 5 (continued) & Pass 6 Fix Backlog

> Repository: `com.agentframework.core`  
> Report date: 2026-05-24  
> Passes covered: **Pass 5** (FINDING-057 → FINDING-064) · **Pass 6** (Prioritised Fix Backlog)

---

## Pass 5 — Findings

### 5.1 — Observability gaps

---

#### FINDING-057 (continued)

| Field | Value |
|---|---|
| **Pass** | 5 |
| **Class** | `com.agentframework.core.DefaultAction` |
| **Method** | `execute()` |
| **Severity** | `[MAJOR]` |
| **Spec ref** | Vol. 1 §"Action Logging for Observability and Replay" |

**Problem**  
Confirm `DefaultAction.execute()` emits `TOOL_CALL_DISPATCHED` before dispatch and `TOOL_CALL_COMPLETED` after, with structured attributes:
`{ "runId", "stepNumber", "toolName", "toolVersion", "inputHash", "dispatchedAt", "completedAt", "status", "retryCount" }`.  
Input parameters must be sanitized of credentials before emission.

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
`RUN_COMPLETED` event — its emission cannot be confirmed. `RUN_STARTED` is confirmed emitted (`ExtendedCoverageTest#15` asserts it). If `RUN_COMPLETED` is not emitted at the end of every run regardless of terminal state (`COMPLETED`, `TERMINATED`, `ABORTED`), operators have no reliable signal that a run has ended for alerting and SLA tracking.

**Evidence**  
`ExtendedCoverageTest#15` checks `RUN_STARTED`. No test checks `RUN_COMPLETED`. No `RUN_COMPLETED` emission confirmed in `AgentRuntime`.

**Fix**  
In `AgentRuntime.executeWith()`, after the `StateMachineRunner.run()` call returns, emit `RUN_COMPLETED` unconditionally with attributes:
`{ "finalState", "terminationReason", "totalCycles", "totalTokens", "durationMs" }`.
This must fire even when the run terminates with an exception.

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
`BELIEF_CONFLICT` event is emitted by `Review.updateBeliefs()` when the returned `Belief.conflicted()` is true. However, the subject logged in the event is only `won.subject() + "|" + won.predicate()` — the conflicting values (old object vs. new object, old confidence vs. new confidence, provenance of both beliefs) are not included. An operator seeing the event cannot reconstruct what the conflict was without querying the conflict log separately.

**Evidence**

```java
// Review.java updateBeliefs():
Map.of("key", won.subject() + "|" + won.predicate())
// No old/new values, no confidence delta, no provenance.
```

**Fix**  
Change to emit full conflict context:

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
No `REPLAY_STARTED` event is emitted when a replay begins. A replay session is a distinct operational event from a normal run — it must be distinguishable in the event stream so operators can correlate replay executions with the original run ID they are replaying from.

**Evidence**  
No `REPLAY_STARTED` in `AgentEvent.EventType` usage found anywhere.

**Fix**  
Add `AgentEvent.EventType.REPLAY_STARTED`. Emit from `AgentRuntime.replay()` before constructing the replay context, with attributes:

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
`GOAL_COMPLETED` event is not explicitly confirmed as emitted when a goal transitions to `COMPLETED`. `Review.updateGoals()` calls `goals.updateStatus(g.id(), GoalStatus.COMPLETED)` — but no corresponding event emission is visible in `Review` or `GoalStack`. Without a `GOAL_COMPLETED` event, operators cannot track goal lifecycle in the event stream.

**Evidence**  
`Review.java updateGoals()` — calls `updateStatus()`, no `emit()`.  
`GoalStack.java` not read directly; cannot confirm it emits on status change.

**Fix**  
Either:
- **(a)** `GoalStack.updateStatus()` emits `GOAL_COMPLETED` / `GOAL_FAILED` events via an injected `EventSink`, **or**
- **(b)** `Review.updateGoals()` emits the event after calling `updateStatus()`.

Option **(a)** is preferable as it captures all goal transitions regardless of call site.

**Tests to add**
- `CoreTest#goalCompleted_emitsGoalCompletedEvent`
- `CoreTest#goalFailed_emitsGoalFailedEvent`

---

### 5.2 — Security gaps

---

#### FINDING-062

| Field | Value |
|---|---|
| **Pass** | 5 |
| **Class** | `com.agentframework.core.Review` |
| **Method** | `step() — working memory write after taint classification` |
| **Severity** | `[BLOCKER]` |
| **Spec ref** | Vol. 1 §"Security — Taint Propagation" |

**Problem**  
`Review.step()` classifies the result taint and correctly assigns it to the `WorkingMemoryEntry`. However, when `taint == HOSTILE`, the entry is **STILL written to working memory** (the `add()` call happens regardless of taint level). A `HOSTILE`-labelled entry in working memory will be included in the next perception step and injected into the LLM's context window. The taint detection is observability-only — it does **NOT** quarantine the content from further reasoning.

**Evidence**

```java
// Review.java step():
TaintLabel taint = classifyResultTaint(result);  // may be HOSTILE
ctx.workingMemory().add(new WorkingMemoryEntry(..., taint));  // always added
if (taint == TaintLabel.HOSTILE) { events.emit(...) }  // event only
// The hostile content is still in working memory after the event fires.
```

**Fix**  
When `taint == TaintLabel.HOSTILE`:
1. Do **NOT** add the full entry to working memory.
2. Add a sanitized placeholder entry: `"Tool result quarantined: hostile content detected [eventId]"` with `TaintLabel.HOSTILE`.
3. Emit `HOSTILE_TAINT_DETECTED` event.
4. `ctx.flagPlanStale("Tool result quarantined — hostile content")`.

This prevents the injected content from reaching the LLM in the next reasoning cycle.

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
`updateBeliefs()` calls `ctx.beliefState().assertBelief(incoming)` where the incoming `Belief` is constructed with `subject="last_tool_result"`, and its value is the raw tool output string. The `Belief` record does not carry a `TaintLabel` field. This means a `HOSTILE`-tainted tool result can be stored as a belief with no taint annotation, and future reasoning that queries the belief state sees clean-looking structured data that originated from a prompt injection.

**Evidence**

```java
// Review.java updateBeliefs():
Belief incoming = new Belief(UUID.randomUUID().toString(),
    "last_tool_result", "equals", value, 0.8,
    "tool_result", Instant.now(), false);
// No taint label on the Belief record.
// Belief.java fields not directly confirmed to include TaintLabel.
```

**Fix**
1. Confirm `Belief` record has a `TaintLabel` field (add if absent).
2. In `updateBeliefs()`, pass the result's classified taint to the `Belief` constructor.
3. In `DefaultBeliefState.assertBelief()`, if `incoming.taintLabel() == HOSTILE`, reject the assertion and log to `conflictLog` with a `TAINT_REJECTED` marker instead of writing it.

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
The integrity hash comparison in `replay()` is likely performed with `String.equals()`, which is not a constant-time comparison. An attacker with timing-oracle access to the replay endpoint could brute-force a valid hash by measuring response times. In production systems where replay is exposed via an API, this is a real timing attack surface.

**Evidence**  
`AgentRuntime.replay()` — hash comparison implementation not directly read, but Java's `String.equals()` short-circuits on first mismatch, producing timing variance proportional to the length of the matching prefix.

**Fix**  
Replace `String.equals(hash1, hash2)` with a constant-time comparison:

```java
MessageDigest.isEqual(hash1.getBytes(UTF_8), hash2.getBytes(UTF_8))
```

This is the standard Java idiom for timing-safe string comparison.

**Test to add**
- `AgentRuntimeTest#replay_integrityCheck_usesConstantTimeComparison`

---

## Pass 6 — Prioritised Fix Backlog

All findings from Passes 1–5 are consolidated and ordered below. Each item is tagged with its source Finding number.

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
| M-1 | FINDING-001 | `StateMachineRunner` | Add `RESPONDING`, `AWAITING_RESULT` handlers (or throw) — they are live states with no handler | 2 h |
| M-2 | FINDING-002 | `StateMachineRunner` | `DEGRADED` → `ABORTED` not `TERMINATED`; make `ABORTED` reachable | 1 h |
| M-3 | FINDING-004 | `GoalStatus` / `GoalStack` | Add `resumeCondition` to `DEFERRED`; add `defer(id, condition)` / `tryResume(ctx)` to `GoalStack` | 1 day |
| M-4 | FINDING-005 | `DefaultLivenessDetector` | Add `checkIntentAlignment(topGoal, activeGoal, ctx)` returning `Optional<TerminationReason>` | 1 day |
| M-5 | FINDING-006 | `DefaultBeliefState` | Inject `EventSink`; emit `BELIEF_CONFLICT` from `assertBelief()` directly | 3 h |
| M-6 | FINDING-007 | `ToolContract` | Confirm / add 4-value `Reversibility` enum: `READ_ONLY`, `IDEMPOTENT_WRITE`, `NON_IDEMPOTENT_WRITE`, `IRREVERSIBLE` | 4 h |
| M-7 | FINDING-008 | `CompositePlanValidator` | Add `ValidationLayer` enum; type each validator layer; enforce ordering and completeness | 1 day |
| M-8 | FINDING-009 | `ValidationResult` | Add `RequiresApproval(reason, approvalToken)` sealed variant; route to `SUSPENDED_HITL` in `StateMachineRunner` | 1 day |
| M-9 | FINDING-010 | `WorkingMemoryTier` / `ContextWindowManager` | Confirm `SUMMARIZED`, `COMPRESSED` tiers exist; replace hard-delete with tier demotion | 1 day |
| M-10 | FINDING-013 | `SimplePerception` | Add goal-scoped relevance filtering; inject `RelevanceScorer` | 1 day |
| M-11 | FINDING-015 | `AgentRuntime` | Add `CURRENT_SCHEMA_VERSION` constant; validate on `replay()` | 2 h |
| M-12 | FINDING-017 | `ToolException` | Add `ToolErrorCode` enum; change `ToolException(ToolErrorCode, String)` | 4 h |
| M-13 | FINDING-020 | `DefaultExecutionContext` | Add `ancestorRunIds` set; check on delegation dispatch; terminate with `ResourceLimit("Delegation cycle")` | 4 h |
| M-14 | FINDING-021 | `DelegateToAgent` | Confirm/add all 5 delegation contract fields; add typed `FailureProtocol` enum | 4 h |
| M-15 | FINDING-022 | `Review` | Extract 7 collaborators: `TaintPolicy`, `WorkingMemoryWriter`, `GoalCompletionPolicy`, `BeliefUpdater`, `MemoryWriteBack`, `WorldChangeRevalidator`, `TerminationEvaluator` | 2 days |
| M-16 | FINDING-023 | `AgentRuntime` | Extract `ExecutionContextFactory`; move integrity check out of `replay()` into a dedicated `SnapshotVerifier` | 1 day |
| M-17 | FINDING-024 | `StateMachineRunner` | Replace switch with `Map<RunState, StepHandler>` dispatch table | 1 day |
| M-18 | FINDING-026 | `ExecutionContext` | Define narrow view interfaces: `PerceptionContext`, `ReasoningContext`, `ActionContext` | 1 day |
| M-19 | FINDING-028 | `StateMachineRunner` | Define `TaintPolicy` interface; inject instead of `new TaintClassifier()` | 3 h |
| M-20 | FINDING-029 | `Review` | Remove inline `new ContextWindowManager()`; inject via `StateMachineRunner` constructor | 2 h |
| M-21 | FINDING-030 | `StateMachineRunner` | Construct `Review` in constructor; store as `private final Review review` | 1 h |
| M-22 | FINDING-031 | `StateMachineRunner` / `Review` | Add `Task.maxRevisions()` (default 3); replace all `isRevisionBudgetExceeded(3)` literals | 2 h |
| M-23 | FINDING-033 | `Review` | Add `Task.maxConsecutiveFailures()` (default 3); replace `MAX_FAILURES` literal | 2 h |
| M-24 | FINDING-037 | `AgentRuntime` | Add `exceptionally()` handler to `executeAsync()`; return structured `ExecutionResult` on unchecked exception | 3 h |
| M-25 | FINDING-038 | `DefaultExecutionContext` | Confirm all mutable counters are `AtomicInteger`; fix any plain `int` fields | 3 h |
| M-26 | FINDING-040 | `AgentRuntime` | Implement `AutoCloseable`; `close()` shuts down injected `ExecutorService` | 1 h |

---

### 🟢 MINORS — Fix in Sprint 3 (18 items)

| # | Finding | Class | Fix summary | Effort |
|---|---------|-------|-------------|--------|
| m-1 | FINDING-012 | `StateMachineRunner` | Emit `wmTokens` attribute in `CYCLE_STARTED` event | 1 h |
| m-2 | FINDING-016 | `DefaultExecutionContext` | Add `CHECKPOINT_SAVED` to `EventType`; emit from `checkpoint()` | 1 h |
| m-3 | FINDING-018 | `ToolContract` | Confirm/add explicit `boolean isIdempotent()` field | 1 h |
| m-4 | FINDING-019 | `ToolContract` | Add `Duration maxStaleness()` field (null = always fresh) | 2 h |
| m-5 | FINDING-027 | `PlanValidator` | Split into `PreActionValidator` and `PostActionValidator` | 3 h |
| m-6 | FINDING-032 | `ContextWindowManager` | Make `EVICTION_THRESHOLD` a constructor parameter with default 0.70 | 30 min |
| m-7 | FINDING-034 | `StateMachineRunner` | Extract `DEFAULT_WALL_CLOCK` as a named constant | 15 min |
| m-8 | FINDING-035 | `Review` | Extract `SUMMARY_MAX_CHARS = 200` as a named, configurable constant | 30 min |
| m-9 | FINDING-039 | `GoalStack` | Back with `ConcurrentLinkedDeque`; add `snapshot()` returning unmodifiable copy | 2 h |
| m-10 | FINDING-041 | `StateMachineRunner` | Remove `Long.MAX_VALUE` sentinel; pass `null` to `Budget` for no-limit | 30 min |
| m-11 | FINDING-049 | All test files | Rename all test methods to `<scenario>_<condition>_<expectedOutcome>`; add Vol. 1 spec references | 1 day |
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
