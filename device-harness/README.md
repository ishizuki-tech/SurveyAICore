# SurveyAICore C5 device harness

This independent Android project establishes the Phase C5A assembled-library boundary. It is not included in the parent SurveyAICore Gradle build and consumes a local release AAR rather than `project(":survey-ai-core")`.

Run `scripts/prepare-aar.sh` before building the app. The script rebuilds SurveyAICore's release AAR, copies it into the ignored `app/libs/` directory, and prints its size and SHA-256.

Because a raw AAR has no publication metadata, the app explicitly resolves LiteRT-LM 0.16.1 and Kotlin coroutines Android 1.11.0. No model is committed, packaged, or provisioned in C5A. C5A does not create a SurveyAICore instance or run inference; later C5B/C5C work will add the smoke flow and model provisioning.
