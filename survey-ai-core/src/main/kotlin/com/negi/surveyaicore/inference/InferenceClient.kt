package com.negi.surveyaicore.inference

import com.negi.surveyaicore.runtime.RuntimeModel

internal interface InferenceClient {
    suspend fun initialize(model: RuntimeModel): Result<Unit>

    suspend fun generate(
        model: RuntimeModel,
        prompt: String,
        onDelta: (String) -> Unit = {},
    ): InferenceOutput

    suspend fun reset(model: RuntimeModel): Result<Unit>

    suspend fun close(model: RuntimeModel?)
}

internal data class InferenceOutput(
    val rawText: String,
    val durationMs: Long,
    val timedOut: Boolean = false,
    val error: String? = null,
)
