package com.negi.surveyaicore.evaluation

internal sealed interface FollowupGenerationOutcome {
    data class Success(
        val question: String,
    ) : FollowupGenerationOutcome

    data class Failure(
        val category: FollowupGenerationFailureCategory,
    ) : FollowupGenerationOutcome
}

internal sealed interface FollowupGenerationFailureCategory {
    data object Timeout : FollowupGenerationFailureCategory

    data object InferenceError : FollowupGenerationFailureCategory

    data object InvalidModelOutput : FollowupGenerationFailureCategory
}
