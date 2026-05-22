<div align="center">

# 🤖 Production Agent Framework

**A production-grade Java 21 framework for building goal-directed, autonomous AI agent systems.**

[![Build Status](https://github.com/tnhmar/production-agent-framework-/actions/workflows/ci.yml/badge.svg)](https://github.com/tnhmar/production-agent-framework-/actions)
[![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Build-Maven-blue?logo=apachemaven)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)
[![Version](https://img.shields.io/badge/version-1.0.0--SNAPSHOT-lightgrey)](pom.xml)

_Based on the architectural specification from **"From LLMs to Agent Ecosystems — Volume 1"**_

</div>

---

## Overview

**Production Agent Framework** implements the four-layer agent capability stack — **Perception → Reasoning → Memory → Action** — as a clean, composable Java library. It provides the scaffolding required to build agents that are goal-directed, autonomous, and capable of closed-loop feedback over multi-step tasks.

The framework separates what the LLM does (reason at each step) from what the system does (persist state, manage goals, execute tools, enforce safety, and determine when a task is complete). It is intentionally LLM-provider-agnostic and dependency-minimal, making it suitable as a reference implementation or an embedded foundation layer.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         AgentRuntime                            │
│                      StateMachineRunner                         │
│  INITIALIZED → VALIDATING → PLANNING → TOOL_EXECUTION →        │
│                MEMORY_UPDATE → VALIDATING → … → TERMINATED      │
└────────┬──────────────┬──────────────┬──────────────┬──────────┘
         │              │              │              │
    Perception      Reasoning       Memory          Action
         │              │              │              │
  ┌──────┴──────┐ ┌─────┴──────┐ ┌────┴────┐ ┌──────┴──────────┐
  │ Normalise   │ │ ReAct /    │ │Episodic │ │ ToolContract    │
  │ Parse       │ │ Chain-of-  │ │Semantic │ │ Validation Stack│
  │ Ground      │ │ Thought    │ │Procedural│ │ Schema→Semantic │
  │ Filter      │ │ strategies │ │Working  │ │ →Policy→Safety  │
  │ RAG inject  │ │            │ │Vector   │ │ →Taint          │
  └─────────────┘ └────────────┘ └─────────┘ └─────────────────┘
```

### Core Packages

| Package | Responsibility |
|---|---|
| `core` | `Agent`, `AgentRuntime`, `StateMachineRunner`, `ExecutionContext`, `GoalStack`, `BeliefState`, `WorkingMemory` |
| `foundation` | Shared value types: `Task`, `Decision`, `ToolCall`, `Observation`, `RunState`, `TaintLabel`, `TrustTier`, `TerminationReason` |
| `perception` | Input normalisation pipeline, grounding, relevance filtering, RAG injection |
| `reasoning` | `Reasoning` interface, `LLMReasoning`, `ReActStrategy`, `PromptBuilder` |
| `memory` | `Memory` interface, tiered implementation, write gate, importance scorer, audit log, knowledge graph, prospective scheduler |
| `action` | `Action`, `ToolContract`, `ToolRegistry`, 4-layer validator chain, parallel execution, middleware pipeline |
| `action.middleware` | `Logging`, `RateLimiting`, `Retry`, `CircuitBreaker`, `Caching`, `HumanApproval` |
| `hitl` | Human-in-the-loop approval service, approval packet, execution store |
| `multi` | `AgentOrchestrator`, `PipelineOrchestrator`, `SupervisorOrchestrator`, `AgentCard`, A2A client/task |
| `security` | `TaintTracker`, `TrustBoundary`, `TenantPolicyEngine`, `SecurityEnforcer` |
| `observability` | `EventSink`, `AgentEvent`, `RunMetrics`, `MetricsSnapshot` |
| `rag` | `RagService`, `RagQuery`, `Passage` |

---

## Key Concepts

### The Agentic Loop

An agent is defined by its loop. The `StateMachineRunner` drives the agent through explicit states:

```
INITIALIZED
    │
    ▼
VALIDATING ◄────────────────────────────────────────────┐
    │  (resource limit check: cycles, tokens, wall-clock) │
    ▼                                                     │
PLANNING                                                  │
    │  perceive → decide → validate plan                  │
    ├─ NeedsCorrection ──► flag plan stale ───────────────┘
    ├─ Failed ──────────► TERMINATED
    ▼
TOOL_EXECUTION
    │
    ▼
MEMORY_UPDATE (Review / Reflect)
    │
    └──────────────────────────────────────────────────── ►
```

### Goal Stack and Belief State

Every run maintains a typed `GoalStack` (push/pop, status tracking) and a `BeliefState` (confidence-weighted, provenance-tracked, conflict-detecting). These are first-class components of `ExecutionContext` — not prompt variables.

### Tool Contract and Validation

Every tool is backed by a `ToolContract` that declares its input schema, output schema, error taxonomy, idempotency, and side-effect class. The 4-layer validation pipeline runs before every dispatch:

```
ToolCall
  │
  ├─[1] SchemaActionValidator    — required fields, type coercion
  ├─[2] SemanticActionValidator  — precondition checks
  ├─[3] SecurityEnforcer         — tenant policy, taint detection
  ├─[4] SafetyActionValidator    — irreversibility gate → REQUIRE_APPROVAL
  └─[5] TaintActionValidator     — hostile taint propagation check
```

**Side-effect classes** map directly to policy implications:

| Class | Policy |
|---|---|
| `READ_ONLY` | Permit by default |
| `IDEMPOTENT_WRITE` | Permit with schema validation |
| `NON_IDEMPOTENT_WRITE` | Require deduplication control |
| `IRREVERSIBLE` | Require human approval |
| `HIGH_BLAST_RADIUS` | Require staged execution |

### Memory Architecture

Five memory types are supported within a unified `Memory` interface:

- **Episodic** — interaction history and session events
- **Semantic** — factual knowledge over external stores (vector + relational)
- **Procedural** — recurring task patterns
- **Working** — live, bounded, token-aware context window
- **Prospective** — scheduled future actions (time-based, idle-based, condition-based)

Writes pass through a `WriteGate` (significance, classification, sensitivity, duplication, TTL) and are recorded by a `MemoryAuditLog`. The `ContextWindowManager` evicts working-memory entries at the 70% token-budget threshold.

---

## Quick Start

### Requirements

- Java 21+
- Maven 3.9+

### Build

```bash
git clone https://github.com/tnhmar/production-agent-framework-.git
cd production-agent-framework-
mvn verify
```

### Minimal Agent Example

```java
// 1. Wire the tool registry
ToolRegistry registry = new SimpleToolRegistry();
registry.register(myEchoToolContract, (args, ctx) ->
    new ToolResult("Echo: " + args.get("text"), List.of(), 10, BigDecimal.ZERO, Duration.ofMillis(5)));

// 2. Assemble the action layer
ToolDispatcher dispatcher = new DefaultToolDispatcher(registry);
ToolMiddleware  middleware = new LoggingMiddleware(new RetryMiddleware(ToolMiddleware.passThrough()));
Action          action     = DefaultAction.withDefaultValidators(
    registry, middleware, dispatcher, new SecurityEnforcer(new TaintTracker(), new TenantPolicyEngine()));

// 3. Assemble memory, perception, and reasoning
Memory     memory     = new TieredMemory(/* backends */);
Perception perception = new SimplePerception();
Reasoning  reasoning  = new LLMReasoning(new StubLLMProvider(), new ReActStrategy(), new PromptBuilder());

// 4. Build the agent
Agent agent = Agent.builder()
    .name("my-agent")
    .perception(perception)
    .reasoning(reasoning)
    .action(action)
    .memory(memory)
    .build();

// 5. Run
AgentRuntime   runtime = new AgentRuntime(new PassThroughPlanValidator());
Task           task    = Task.builder()
    .instruction("Echo the word: hello")
    .maxCycles(10).maxTokens(10_000)
    .build();

ExecutionResult result = runtime.execute(agent, task);
System.out.println(result.finalAnswer());   // "Echo: hello"
```

### Multi-Agent Pipeline

```java
List<AgentHandle> pipeline = List.of(
    new AgentHandle.Local(runtime, extractorAgent),
    new AgentHandle.Local(runtime, summarizerAgent),
    new AgentHandle.Remote(a2aClient, remoteFormatterCard)
);

MultiAgentResult result = new PipelineOrchestrator()
    .coordinate(task, pipeline, ctx);
```

---

## Testing

Each subsystem has a dedicated test suite under `src/test/java/com/agentframework/tests/`:

| Test Class | Covers |
|---|---|
| `CoreTest` | `ExecutionContext`, `GoalStack`, `BeliefState`, `WorkingMemory` |
| `RuntimeTest` | `AgentRuntime`, `StateMachineRunner`, termination paths |
| `ActionTest` | Validation pipeline, parallel execution, middleware chain |
| `MemoryTest` | `TieredMemory`, `WriteGate`, `MemoryAuditLog`, retrieval |
| `ReasoningTest` | `ReActStrategy` parsing, `LLMReasoning` prompt composition |
| `SecurityTest` | `TaintTracker`, `TenantPolicyEngine`, `TrustBoundary` |
| `HitlTest` | Approval packet, `AutoApprovalService`, `AutoRejectService` |
| `MultiAgentTest` | `PipelineOrchestrator`, `SupervisorOrchestrator`, A2A tasks |
| `ObservabilityPerceptionTest` | Event emission, `InputNormalizationPipeline`, `RelevanceFilter` |
| `FoundationTest` | Value types: `Decision`, `ToolCall`, `TaintLabel`, `TrustTier` |

Run all tests:

```bash
mvn test
```

CI runs on every push to `main` and `develop` via GitHub Actions (JDK 21 Temurin). Test reports are uploaded as workflow artefacts.

---

## Human-in-the-Loop (HITL)

Irreversible actions trigger an approval gate before execution. The `ApprovalService` interface supports synchronous (`AutoApprovalService` for tests) and asynchronous implementations for production UI integration:

```java
// Register a custom approval service (e.g., Slack bot, web UI)
ApprovalService approvalService = packet -> {
    // serialize packet, post to review queue, block until decision
    return reviewQueue.awaitDecision(packet.approvalId(), packet.timeToLive());
};
```

`ApprovalDecision` is a sealed type: `Approved`, `Rejected`, `Modified` (returns a revised `ToolCall`), or `Escalated`.

> **Note:** The synchronous `AgentRuntime` logs a warning and aborts on `SUSPENDED_HITL`. Full pause-and-resume requires an async runtime that honours the `SUSPENDED_HITL` state — this is the designated extension point.

---

## Security Model

The security model is layered across validation and memory:

- **Taint propagation** — every `Observation` carries a `TaintLabel` (`CLEAN / EXTERNAL / HOSTILE`). `TaintTracker.propagate()` applies max-severity semantics to derived values.
- **Trust tiers** — `TrustTier` (`HIGH / MEDIUM / LOW / UNTRUSTED`) gates cross-boundary data flow via `TrustBoundary`.
- **Tenant isolation** — `TenantPolicyEngine` enforces per-tenant tool allowlists, cycle and token ceilings, and irreversible-action permissions.
- **Hostile input blocking** — `SecurityEnforcer` blocks all tool dispatch when hostile taint is detected in working memory.

---

## Observability

All significant runtime events are emitted to `EventSink`:

```
RUN_STARTED → CYCLE_STARTED → PLAN_STALE? →
MODEL_CALL_STARTED → TOOL_CALLED → TOOL_RESULT →
MEMORY_WRITTEN → CYCLE_COMPLETED → … → RUN_COMPLETED
```

`InMemoryEventSink` is provided for testing. Wire `RunMetrics` to Prometheus, OpenTelemetry, or any time-series backend for production observability.

---

## Design Principles

- **LLM is the cognitive core, not the agent.** Agent behaviour emerges from the architecture surrounding the model — not from the model itself.
- **Layers are strict contracts.** `Perception`, `Reasoning`, `Memory`, and `Action` are `interface` types. Implementations are substitutable without touching the runtime.
- **Safety is structural.** Action classification, taint propagation, and tenant policy are enforced in the framework — not in prompt text.
- **State is explicit.** `GoalStack`, `BeliefState`, and `WorkingMemory` are typed data structures — not prompt variables embedded in strings.
- **Zero mandatory external dependencies.** The core framework compiles and runs with only the JDK. LLM providers, vector stores, and message brokers are extension points — not compile-time requirements.

---

## Roadmap

- [ ] Jackson Databind integration (robust JSON parsing for `ReActStrategy`)
- [ ] Full checkpoint serialisation (goal stack + belief state + integrity hash)
- [ ] Async runtime with `SUSPENDED_HITL` resume support
- [ ] Tier-aware working-memory eviction
- [ ] OpenTelemetry `EventSink` adapter
- [ ] AgentCard endpoint URL field (A2A protocol compliance)
- [ ] RAG reranking and passage selection pipeline
- [ ] ANP / W3C DID layer (Vol. 2 scope)

---

## Related Work

This framework is the reference implementation accompanying the book series **"From LLMs to Agent Ecosystems"** by the same author. Volume 1 covers the full specification — the agent loop, memory taxonomy, action safety model, cognitive architectures, multi-agent coordination, and security model — that this codebase implements.

---

## License

MIT License. See [LICENSE](LICENSE) for details.

---

<div align="center">
<sub>Built with Java 21 · Designed for production · Grounded in specification</sub>
</div>
