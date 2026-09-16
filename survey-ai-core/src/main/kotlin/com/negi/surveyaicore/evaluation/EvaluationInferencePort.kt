package com.negi.surveyaicore.evaluation

import com.negi.surveyaicore.SurveyAICore

internal interface EvaluationInferencePort {
    suspend fun generate(prompt: String): EvaluationInferenceResult
}

internal sealed interface EvaluationInferenceResult {
    data class Success(
        val rawOutput: String,
    ) : EvaluationInferenceResult

    data object Timeout : EvaluationInferenceResult

    data object Failure : EvaluationInferenceResult
}

internal class SurveyAICoreEvaluationInferencePort(
    private val core: SurveyAICore,
) : EvaluationInferencePort {
    override suspend fun generate(prompt: String): EvaluationInferenceResult {
        val result = core.generate(prompt)
        return when {
            result.timedOut -> EvaluationInferenceResult.Timeout
            result.errorMessage != null -> EvaluationInferenceResult.Failure
            else -> EvaluationInferenceResult.Success(result.text)
        }
    }
}
