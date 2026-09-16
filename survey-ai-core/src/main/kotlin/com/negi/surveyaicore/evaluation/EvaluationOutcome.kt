package com.negi.surveyaicore.evaluation

internal sealed interface EvaluationOutcome {
    data class Complete(
        val evaluation: AnswerEvaluation,
    ) : EvaluationOutcome

    data class Incomplete(
        val evaluation: AnswerEvaluation,
    ) : EvaluationOutcome

    data class Failure(
        val category: EvaluationFailureCategory,
    ) : EvaluationOutcome
}

internal sealed interface EvaluationFailureCategory {
    data object Timeout : EvaluationFailureCategory

    data object InferenceError : EvaluationFailureCategory

    data object InvalidModelOutput : EvaluationFailureCategory
}
