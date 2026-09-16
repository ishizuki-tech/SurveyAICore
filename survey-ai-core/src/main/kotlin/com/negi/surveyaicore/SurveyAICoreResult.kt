package com.negi.surveyaicore

import com.negi.surveyaicore.inference.InferenceOutput

data class SurveyAICoreResult(
    val text: String,
    val durationMs: Long,
    val timedOut: Boolean,
    val errorMessage: String?,
)

internal fun InferenceOutput.toSurveyAICoreResult(): SurveyAICoreResult =
    SurveyAICoreResult(
        text = rawText,
        durationMs = durationMs,
        timedOut = timedOut,
        errorMessage = error,
    )
