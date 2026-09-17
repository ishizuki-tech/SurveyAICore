# SurveyAICore Roadmap

## Current Status

SurveyAICore has completed the core offline AI pipeline and real-device Core characterization.

Current `main` includes:

- LiteRT-LM runtime integration
- Public runtime facade
- Internal answer evaluator
- Internal follow-up generator
- Internal follow-up orchestrator
- C5 consumer-AAR harness
- C6 runtime validation scenarios
- C7 device characterization support
- JVM CI
- Project README

The next major goal is to expose the minimum stable public integration boundary required by Survey2026.

---

## Architecture Status

```text
Runtime                  COMPLETE
Evaluator                COMPLETE (internal)
Follow-Up Generator      COMPLETE (internal)
Orchestration            COMPLETE (internal)
Device Characterization  COMPLETE as historical checkpoint evidence
Public Integration API   NOT STARTED
Survey2026 Integration   NOT STARTED
Survey E2E               NOT STARTED
API Hardening            NOT STARTED
```

Core device characterization exists, but the checked-in C6 and C7 runners remain tied to their historical validation branches rather than current `main`.

---

# Roadmap

## Phase 1 — Minimal Public Integration Contract

### Goal

Define the smallest public API that Survey2026 actually needs.

### Main Question

Do not automatically expose the internal architecture as three separate public APIs.

Prefer evaluating whether one high-level stateless facade is sufficient.

Conceptually:

```text
Survey2026
    ↓
Public Core Facade
    ↓
Internal Orchestrator
    ↓
Evaluator
    ↓
Follow-Up Generator
```

Possible high-level API shape:

```text
advance(request)
    ↓
Completed
NeedFollowup(question)
Stopped(reason)
Failure(...)
```

Exact names should be decided from repository evidence, not assumed in advance.

### Must Remain Internal

- `InferenceClient`
- `LiteRtInferenceClient`
- `RuntimeModel`
- `SLM`
- `LiteRtLM`
- prompt builders
- strict parsers
- native lifecycle coordination
- LiteRT Engine / Conversation types

### Must Not Change

- runtime lifecycle
- native conversation semantics
- evaluator behavior
- follow-up generation behavior
- Survey YAML
- Survey UI
- Survey persistence
- Whisper/VAD

### Device Test

No.

### Completion Criteria

A minimal public contract is approved and does not leak internal runtime or Survey-specific implementation details.

---

## Phase 2 — Public Stateless Facade

### Goal

Implement the approved public integration boundary.

### Design

The public facade should wrap existing internal components rather than duplicate them.

```text
Public Facade
    ↓
Internal FollowupOrchestrator
    ↓
AnswerEvaluator
    ↓
FollowupGenerator
```

The facade should be stateless with respect to Survey product state.

The caller explicitly supplies:

- question
- expected answer target
- original answer
- answered follow-ups
- completion policy
- follow-up capacity

The caller receives typed outcomes.

### Survey Ownership

Survey2026 continues to own:

- YAML
- question configuration
- UI
- navigation
- persistence
- pending follow-up question
- answered-follow-up history
- feature flags
- analytics
- Whisper/VAD
- model download/storage

### Core Ownership

SurveyAICore owns:

- evaluation mechanics
- evaluation prompt construction
- strict parsing
- completion policy
- follow-up generation
- follow-up validation
- orchestration decision
- runtime inference
- native lifecycle

### Device Test

No.

### Completion Criteria

The public facade has deterministic JVM tests for all success and failure outcomes.

---

## Phase 3 — AAR Consumer Contract Test

### Goal

Prove that an external Android consumer can use the new public API through the release AAR.

### Validation

The consumer must compile using only public API.

It must not require:

- internal imports
- reflection
- source-project access
- test-only visibility
- model execution

Conceptually:

```text
SurveyAICore Release AAR
        ↓
External Consumer Fixture
        ↓
Public Integration API
```

### Tests

- Release AAR assembly
- Consumer compilation
- Public DTO usage
- Public outcome handling

### Device Test

No.

### Completion Criteria

The external consumer compiles successfully against the release AAR without accessing internal implementation details.

---

## Phase 4 — Survey2026 One-Node Feature-Gated Integration

### Goal

Connect exactly one Survey2026 node to SurveyAICore.

Do not migrate the entire survey.

### Routing

```text
Feature Gate OFF
    ↓
Existing Survey AI Path

Feature Gate ON
    ↓
SurveyAICore Public Facade
```

### Survey Adapter Responsibilities

The Survey adapter maps existing product state into the Core request.

It should map:

- YAML question
- expected answer target
- original answer
- answered follow-ups
- threshold
- max follow-ups

The adapter maps Core outcomes back into existing Survey domain state.

### Important Contract Difference

Current Core evaluator model output:

```json
{
  "score": 90,
  "missing_points": []
}
```

Current Survey product path also uses:

```json
{
  "score": 90,
  "missing_points": [],
  "followup_needed": false
}
```

The new integration should not force Core back to the older three-field model-output contract.

`followup_needed` should become a deterministic decision derived from Core evaluation/policy rather than a required model-generated field.

### Must Not Change

- YAML schema during the first integration
- unrelated Survey nodes
- Whisper/VAD
- legacy behavior outside the feature gate
- Core runtime semantics

### Device Test

Not required for the first implementation checkpoint.

### Completion Criteria

One node can switch safely between legacy and Core-backed behavior while preserving the same Survey-owned state.

---

## Phase 5 — One-Node Real-Device E2E Validation

### Goal

Validate the complete product flow on a physical device.

### Flow

```text
User Answer
    ↓
Core Evaluation
    ↓
Incomplete
    ↓
Core Follow-Up Generation
    ↓
Survey UI Displays Question
    ↓
User Answers
    ↓
Survey Persists Answer
    ↓
Core Re-evaluates
    ↓
Completed
    ↓
Survey Continues
```

### Scenarios

Validate at minimum:

- complete answer
- incomplete answer
- generated follow-up
- follow-up completion
- capacity exhaustion
- invalid model output
- cancellation
- re-entry / resumed UI state
- legacy rollback path

### Device Test

Yes.

One bounded and explicitly authorized real-model run.

### Completion Criteria

Evidence confirms:

- correct UI behavior
- correct persistence
- correct follow-up history
- correct navigation
- correct rollback
- expected accelerator/backend behavior

---

## Phase 6 — Selected-Node Expansion

### Goal

Expand from one node to a small set of Survey2026 nodes.

### Expansion Strategy

```text
1 Node
  ↓
2–3 Selected Nodes
  ↓
Q8–Q17
  ↓
Broader Survey Coverage
```

Expansion should remain feature-gated and incremental.

### Device Test

Targeted validation as needed.

### Completion Criteria

Multiple configured nodes work without introducing prompt, persistence, or routing regressions.

---

## Phase 7 — Public API / AAR Hardening

### Goal

Stabilize the external API after real Survey integration has validated the contract.

### Scope

- public naming
- KDoc
- error semantics
- lifecycle documentation
- AAR packaging
- API compatibility checks
- binary compatibility strategy
- versioning policy
- consumer documentation

### Important

Do not freeze a large public API before Survey2026 proves which parts are actually needed.

### Device Test

No, unless runtime behavior changes.

### Completion Criteria

The public contract is documented, compatibility-tested, and suitable for continued external use.

---

## Phase 8 — Broader Device Validation

### Goal

Validate the integrated product across additional Android hardware.

Priority examples:

- Pixel 9a
- Samsung Galaxy S24
- Samsung Galaxy S25

### Areas to Observe

- GPU backend behavior
- LiteRT delegate differences
- memory pressure
- thermal behavior
- inference latency
- model loading
- Whisper + Gemma coexistence
- vendor-specific Android differences

### Device Test

Yes.

### Completion Criteria

Supported-device expectations are backed by real evidence rather than emulator assumptions.

---

## Phase 9 — Legacy AI Retirement

### Goal

Remove the old Survey AI path only after the new Core-backed path has demonstrated stable product behavior.

### Preconditions

- one-node validation complete
- multi-node expansion complete
- rollback confidence established
- persistence compatibility confirmed
- device evidence available
- no unresolved product regressions

### Device Test

Yes, before final removal.

### Completion Criteria

Legacy AI path can be removed without losing product functionality or rollback safety.

---

# Optional Experiments

These are intentionally not part of the required integration path.

## Context Size Experiment

Compare:

```text
maxTokens = 512
vs
maxTokens = 1024
```

Only run if product evidence shows the current context capacity is insufficient.

---

## Combined One-Step Inference

Possible experiment:

```text
Evaluate + Generate Follow-Up
```

in one model invocation.

This is optional.

The current two-step design should remain the baseline because it has clearer:

- failure boundaries
- policy behavior
- capacity accounting
- diagnostics

---

## Shared Native Conversation Per Question

Current behavior intentionally uses fresh native conversation semantics per operation.

Do not introduce shared conversation state unless a measured product need justifies changing runtime semantics.

Potential risks include:

- hidden context leakage
- lifecycle complexity
- harder recovery
- increased memory pressure

---

## Broader Model Matrix

Additional models can be tested after a supported-model policy is defined.

This is not required for initial Survey2026 integration.

---

# Explicitly Postponed

The following should not be part of the immediate implementation:

- broad Survey migration
- legacy-path removal
- shared native conversation
- combined one-step inference
- hidden retry behavior
- context-size tuning
- broad model matrix
- broad device matrix
- large public configuration surface
- C6/C7 current-main runner maintenance

---

# Immediate Next Steps

## Step 1 — Public Integration Contract Design

Repository:

```text
SurveyAICore
```

Suggested branch:

```text
codex/public-integration-contract
```

Task:

Design the minimum public stateless facade.

Do not implement Survey integration yet.

Stop if the proposed contract requires:

- YAML
- UI
- persistence
- native runtime types
- hidden state

---

## Step 2 — Public Facade + AAR Consumer Test

Repository:

```text
SurveyAICore
```

Task:

Implement the approved public facade and prove that an external AAR consumer can compile against it.

No device execution required.

---

## Step 3 — One-Node Survey Adapter

Repository:

```text
Surveys0828
```

Suggested branch:

```text
codex/core-one-node-adapter
```

Task:

Connect exactly one Survey node through a feature-gated adapter.

Use fake/test Core responses first.

Do not alter unrelated Survey nodes.

---

# Final Roadmap

```text
Core Runtime
    ✅

Evaluator
    ✅

Follow-Up Generator
    ✅

Orchestrator
    ✅

Core Device Characterization
    ✅

        ↓

1. Minimal Public Integration Contract
        ↓
2. Public Stateless Facade
        ↓
3. AAR Consumer Contract Test
        ↓
4. Survey2026 One-Node Feature-Gated Integration
        ↓
5. One-Node Real-Device E2E
        ↓
6. Selected-Node Expansion
        ↓
7. Public API / AAR Hardening
        ↓
8. Pixel + Samsung Product Validation
        ↓
9. Legacy AI Retirement
```

## Current Immediate Priority

The immediate next task is:

```text
Design the minimum public integration contract.
```

Do not start Survey2026 migration until that contract has been independently reviewed and proven usable through the AAR.
