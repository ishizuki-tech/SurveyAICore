package com.negi.surveyaicore.evaluation

internal data class EvaluationPolicyConfig(
    val completionScoreThreshold: Int = 90,
    val maxAllowedMissingPoints: Int = 0,
) {
    init {
        require(completionScoreThreshold in 1..100) {
            "completionScoreThreshold must be in 1..100"
        }
        require(maxAllowedMissingPoints >= 0) {
            "maxAllowedMissingPoints must be at least 0"
        }
    }
}

internal class EvaluationPolicy(
    private val config: EvaluationPolicyConfig,
) {
    fun apply(evaluation: AnswerEvaluation): EvaluationOutcome =
        if (
            evaluation.score >= config.completionScoreThreshold &&
            evaluation.missingPoints.size <= config.maxAllowedMissingPoints
        ) {
            EvaluationOutcome.Complete(evaluation)
        } else {
            EvaluationOutcome.Incomplete(evaluation)
        }
}
