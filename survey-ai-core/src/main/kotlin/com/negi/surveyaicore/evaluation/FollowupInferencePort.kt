package com.negi.surveyaicore.evaluation

import com.negi.surveyaicore.SurveyAICore

internal interface FollowupInferencePort {
    suspend fun generate(prompt: String): FollowupInferenceResult
}

internal sealed interface FollowupInferenceResult {
    data class Success(
        val rawOutput: String,
    ) : FollowupInferenceResult

    data object Timeout : FollowupInferenceResult

    data object Failure : FollowupInferenceResult
}

internal class SurveyAICoreFollowupInferencePort(
    private val core: SurveyAICore,
) : FollowupInferencePort {
    override suspend fun generate(prompt: String): FollowupInferenceResult {
        val result = core.generate(prompt)
        return when {
            result.timedOut -> FollowupInferenceResult.Timeout
            result.errorMessage != null -> FollowupInferenceResult.Failure
            else -> FollowupInferenceResult.Success(result.text)
        }
    }
}
