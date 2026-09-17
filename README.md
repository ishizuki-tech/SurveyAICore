# SurveyAICore

SurveyAICore is an Android library for local, model-file-backed survey AI. It uses LiteRT-LM for text generation and contains internal building blocks for answer evaluation, follow-up question generation, and bounded follow-up orchestration. The public API is deliberately small: applications own their survey UI, persistence, prompts, and orchestration integration.

## Current status

**Completed**

- Android library module (`survey-ai-core`) targeting API 26+.
- Local model initialization, streamed text generation, lifecycle-aware close, CPU/GPU configuration, and LiteRT-LM integration.
- Internal strict answer evaluation, follow-up generation, and orchestration components.
- Consumer-AAR C5 and runtime-validation C6 harness projects.
- C7 Android instrumentation support for preflight, evaluator, generator, and orchestration characterization.

**Validated in this repository**

- JVM tests cover the public Core types, lifecycle coordination, runtime-model identity, evaluation parsing/policy, follow-up validation, and orchestration control flow.
- The C5/C6 harness and C7 instrumentation source provide bounded, model-identity-checked device-validation workflows. Generated model files, APKs, and captured device logs are intentionally not tracked in this repository.

**Not yet provided as a public product API**

- Public evaluator, follow-up generator, or orchestration entry points.
- Survey2026 application integration.
- A one-call combined evaluate-and-follow-up API.
- Shared native conversation state for a whole survey question.
- Broad device or model compatibility characterization.

## Architecture

### Runtime

```text
SurveyAICore
  -> InferenceClient
    -> LiteRtInferenceClient
      -> SLM
        -> LiteRtLM
          -> LiteRT-LM Engine / Conversation
```

`SurveyAICore.create()` validates a readable, non-empty local model file, loads the LiteRT native library, and initializes the internal inference client. Inference is serialized by a process-wide gate. `close()` stops new work, waits for admitted generations to drain, then performs cleanup in a non-cancellable context; concurrent callers share the same close completion.

`generate()` streams deltas to an optional callback and returns a `SurveyAICoreResult`. Cancellation is propagated. Other runtime failures are returned in the result, including timeout classification when applicable. The internal client resets the native conversation after an inference reaches its cleanup safepoint, so callers should pass any application-level history in their prompts rather than relying on an implicit long-lived conversation.

### Internal AI flow

```text
Answer + survey context
  -> AnswerEvaluator
    -> Complete | Incomplete
      -> FollowupGenerator
        -> NeedFollowup(question)
          -> caller stores AnsweredFollowup
            -> FollowupOrchestrator re-evaluates
```

`AnswerEvaluator`, `FollowupGenerator`, and `FollowupOrchestrator` are internal implementation types. The application layer is responsible for presenting a question, collecting the response, persisting answered follow-ups, and invoking the next internal step when an integration is added.

## Public API

The currently public API consists of:

- `SurveyAICore.create(context, modelFile, config)`
- `SurveyAICore.generate(prompt, onDelta)`
- `SurveyAICore.close()`
- `SurveyAICoreConfig`, `SurveyAICoreAccelerator`, and `SurveyAICoreResult`

```kotlin
val core = SurveyAICore.create(
    context = context,
    modelFile = modelFile,
    config = SurveyAICoreConfig(
        accelerator = SurveyAICoreAccelerator.GPU,
    ),
)

try {
    val result = core.generate("Answer in one short sentence: What is the capital of Japan?") { delta ->
        // Render or collect streamed text.
    }
    if (!result.timedOut && result.errorMessage == null) {
        println(result.text)
    }
} finally {
    core.close()
}
```

The calls are `suspend` functions and must run from a coroutine. A model file must exist, be a regular readable file, and be non-empty before creation.

## Evaluation

The internal evaluator makes one inference through a narrow internal port and parses a JSON object containing these required fields:

```json
{
  "score": 90,
  "missing_points": []
}
```

`score` must be an unquoted integer from 1 through 100. Every `missing_points` entry must be a nonblank string. Blank output, non-object JSON, and invalid field types or values are rejected.

Completion is decided in Kotlin, not delegated to model prose: by default the score must be at least 90 and the number of missing points must be zero. Model timeouts, inference failures, and invalid model output map to typed internal failure outcomes; cancellation propagates.

## Follow-up generation

The internal generator makes one inference and accepts only one plain-text follow-up question. `FollowupResultValidator` rejects blank output, JSON, Markdown fences, explanatory prefixes, multiline text, multiple question marks, and output longer than 280 Unicode code points. A valid question ends with exactly one supported terminal mark: `?`, `？`, or `؟`.

## Orchestration

`FollowupOrchestrator` first evaluates the original answer and any supplied answered-follow-up history. A complete evaluation returns `Completed`. An incomplete evaluation either generates one follow-up while capacity remains or returns `Stopped(FollowupCapacityExhausted)`. The caller appends the question and response as `AnsweredFollowup`, then calls `advance()` again for a fresh evaluation.

The orchestration policy is internal and receives an explicit `maxFollowups`. It does not persist state, own UI, or expose a public workflow API.

## Runtime configuration

`SurveyAICoreConfig` defaults are:

| Setting | Default |
| --- | --- |
| Accelerator | `GPU` |
| `maxTokens` | `512` |
| `topK` | `1` |
| `topP` | `0.0f` |
| `temperature` | `0.0f` |

`maxTokens` is passed to the runtime as the Engine token-capacity setting; it is not the per-request output limit. The internal LiteRT-LM text path uses a 64-token per-request decode cap.

## Testing and validation

Run the repository JVM tests:

```bash
./gradlew :survey-ai-core:testDebugUnitTest
```

Build the Android instrumentation APK without running it:

```bash
./gradlew :survey-ai-core:assembleDebugAndroidTest
```

The separate `device-harness` Gradle project consumes an assembled release AAR instead of the library project directly.

- **C5** provides consumer-AAR smoke tooling and model-identity checks.
- **C6** provides explicit runtime scenarios for foundation, warm reuse, cancellation recovery, create/close lifecycle recovery, multiple-instance serialization, and CPU-to-GPU boundaries.
- **C7** provides isolated Core Android instrumentation methods for preflight, evaluator characterization, generator characterization, and a single orchestration scenario. Its runner checks a supplied model size and SHA-256, uses a dedicated test package, and bounds an instrumentation invocation.

Historical C6 and C7 device-validation evidence is retained. Their checked-in runners are intentionally branch-gated to `phase-c6-runtime-validation` and `codex/device-characterization`, respectively, preserving the exact source contexts used for that physical-device evidence. Do not execute either runner directly from current `main` without deliberate runner maintenance or update.

Device tests require an explicitly supplied compatible device and model. They are not run by the commands above.

## CI

GitHub Actions workflow **SurveyAICore PR Unit Tests** runs `./gradlew :survey-ai-core:testDebugUnitTest` for pull requests and for pushes to `main` using Java 17.

## Current limitations

- The library has no public high-level evaluation or follow-up API yet.
- Survey persistence, UI, and application integration are outside this repository.
- Evaluation and generation remain separate inference operations; there is no combined one-step model call.
- Native conversation history is reset after requests rather than shared for a whole survey question.
- The repository does not package model files or version device evidence.

## Next development stage

The next supported milestone is to design a stable public integration boundary for the internal evaluator and follow-up flow, then integrate it through an application layer without expanding the current runtime contract implicitly.

## License

This project is licensed under the [MIT License](LICENSE).
