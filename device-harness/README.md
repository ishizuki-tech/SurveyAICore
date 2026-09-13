# SurveyAICore C5 device harness

This independent Android project establishes the Phase C5A assembled-library boundary. It is not included in the parent SurveyAICore Gradle build and consumes a local release AAR rather than `project(":survey-ai-core")`.

Run `scripts/prepare-aar.sh` before building the app. The script rebuilds SurveyAICore's release AAR, copies it into the ignored `app/libs/` directory, and prints its size and SHA-256.

Because a raw AAR has no publication metadata, the app explicitly resolves LiteRT-LM 0.16.1 and Kotlin coroutines Android 1.11.0. No model is committed, packaged, or provisioned in C5A.

C5B adds a harness-only public `SurveyAICore` smoke runner plus one instrumentation test. By default, the test intentionally skips before runner entry when `files/model.litertlm` is absent. C5C will provision and verify that app-private model, then run the same test with `c5.requireModel=true`; in that mode a missing model is a hard failure. C5B does not claim real inference success.

## C5C verified device-smoke tooling

C5A is the independent assembled-AAR bootstrap and C5B is the public smoke-runner and instrumentation checkpoint. C5C1 adds tooling only; it does not transfer a model or run inference. C5C2 will use the tooling to perform one verified required-model smoke, and C5C3 will review and checkpoint the resulting scripts and documentation.

The required model identity is 4,919,541,760 bytes with SHA-256 `2e67a6cd51dfe0f793431e6bd4ed8d029c88e10f52ca0469ad38445e3cd3c1f4`. Supply the model path explicitly:

```text
scripts/provision-model.sh <model-path> [serial]
scripts/provision-model.sh --check <model-path> [serial]
scripts/run-c5-smoke.sh <model-path> [serial]
scripts/run-c5-smoke.sh --check <model-path> [serial]
```

`--check` is non-mutating. Normal `run-c5-smoke.sh` is a C5C2-only operation: it refreshes the AAR, builds and installs both APKs before provisioning, verifies an app-private partial-to-final model transfer, then runs exactly one required-model smoke. Do not use normal mode until C5C1 receives independent review. Neither script commits models, APKs, AARs, logs, or generated evidence.

## C6 runtime-validation foundation

C6 keeps the C5 smoke source and C5C evidence frozen. Its additive harness uses only the assembled AAR public API and assigns independent session, scenario, instance, and request identities; these harness identifiers are not LiteRT native run IDs.

`scripts/run-c6.sh` has no default scenario. It requires an explicit reviewed selector and currently exposes only the non-inference `foundation` selector. Future C6 slices add their own selectors only after source review. The script verifies the C6 source baseline, Pixel 9a identity, package/run-as access, and the existing app-private model before doing any future replacement install. It never provisions, overwrites, deletes, or retransfers a model; a missing or mismatched model is a hard stop.

Future C6 runs use replacement installation to preserve app data, then recheck `files/model.litertlm`. Fast identity checking verifies presence and exact size; `--model-check full` also verifies the full SHA-256. Each run emits a `C6_RUN_BOUNDARY_<session-id>_<scenario-id>` marker and stores instrumentation output plus boundary-scoped logcat outside tracked source. C6 does not clear logcat as its evidence boundary and does not auto-retry a failed scenario. C6A itself performs no real inference.
