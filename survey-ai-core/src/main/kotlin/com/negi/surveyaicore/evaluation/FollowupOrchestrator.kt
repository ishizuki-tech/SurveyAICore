package com.negi.surveyaicore.evaluation

internal data class FollowupSessionState(
    val question: String,
    val expectedAnswerTarget: String,
    val originalAnswer: String,
    val answeredFollowups: List<AnsweredFollowup> = emptyList(),
) {
    init {
        require(question.isNotBlank()) { "question must not be blank" }
        require(expectedAnswerTarget.isNotBlank()) { "expectedAnswerTarget must not be blank" }
    }
}

internal data class FollowupOrchestrationPolicy(
    val maxFollowups: Int,
) {
    init {
        require(maxFollowups >= 0) { "maxFollowups must be at least 0" }
    }
}

internal sealed interface FollowupStepOutcome {
    data class Completed(
        val evaluation: AnswerEvaluation,
    ) : FollowupStepOutcome

    data class NeedFollowup(
        val question: String,
    ) : FollowupStepOutcome

    data class Stopped(
        val reason: StopReason,
        val evaluation: AnswerEvaluation,
    ) : FollowupStepOutcome

    data class Failure(
        val stage: FollowupFailureStage,
        val category: FollowupStepFailureCategory,
    ) : FollowupStepOutcome
}

internal sealed interface StopReason {
    data object FollowupCapacityExhausted : StopReason
}

internal enum class FollowupFailureStage {
    Evaluation,
    Generation,
}

internal enum class FollowupStepFailureCategory {
    Timeout,
    InferenceError,
    InvalidModelOutput,
}

internal class FollowupOrchestrator(
    private val evaluator: AnswerEvaluator,
    private val generator: FollowupGenerator,
    private val policy: FollowupOrchestrationPolicy,
    private val evaluationPolicyConfig: EvaluationPolicyConfig = EvaluationPolicyConfig(),
) {
    suspend fun advance(state: FollowupSessionState): FollowupStepOutcome {
        val evaluationOutcome =
            evaluator.evaluate(
                input =
                    EvaluationInput(
                        question = state.question,
                        expectedAnswerTarget = state.expectedAnswerTarget,
                        originalAnswer = state.originalAnswer,
                        answeredFollowups = state.answeredFollowups,
                    ),
                policyConfig = evaluationPolicyConfig,
            )

        return when (evaluationOutcome) {
            is EvaluationOutcome.Complete -> FollowupStepOutcome.Completed(evaluationOutcome.evaluation)
            is EvaluationOutcome.Failure ->
                FollowupStepOutcome.Failure(
                    stage = FollowupFailureStage.Evaluation,
                    category = evaluationOutcome.category.toStepFailureCategory(),
                )
            is EvaluationOutcome.Incomplete -> {
                if (state.answeredFollowups.size >= policy.maxFollowups) {
                    FollowupStepOutcome.Stopped(
                        reason = StopReason.FollowupCapacityExhausted,
                        evaluation = evaluationOutcome.evaluation,
                    )
                } else {
                    generateFollowup(state, evaluationOutcome.evaluation)
                }
            }
        }
    }

    private suspend fun generateFollowup(
        state: FollowupSessionState,
        evaluation: AnswerEvaluation,
    ): FollowupStepOutcome {
        val generationOutcome =
            generator.generate(
                FollowupGenerationInput(
                    question = state.question,
                    expectedAnswerTarget = state.expectedAnswerTarget,
                    originalAnswer = state.originalAnswer,
                    answeredFollowups = state.answeredFollowups,
                    missingPoints = evaluation.missingPoints,
                ),
            )

        return when (generationOutcome) {
            is FollowupGenerationOutcome.Success -> FollowupStepOutcome.NeedFollowup(generationOutcome.question)
            is FollowupGenerationOutcome.Failure ->
                FollowupStepOutcome.Failure(
                    stage = FollowupFailureStage.Generation,
                    category = generationOutcome.category.toStepFailureCategory(),
                )
        }
    }

    private fun EvaluationFailureCategory.toStepFailureCategory(): FollowupStepFailureCategory =
        when (this) {
            EvaluationFailureCategory.Timeout -> FollowupStepFailureCategory.Timeout
            EvaluationFailureCategory.InferenceError -> FollowupStepFailureCategory.InferenceError
            EvaluationFailureCategory.InvalidModelOutput -> FollowupStepFailureCategory.InvalidModelOutput
        }

    private fun FollowupGenerationFailureCategory.toStepFailureCategory(): FollowupStepFailureCategory =
        when (this) {
            FollowupGenerationFailureCategory.Timeout -> FollowupStepFailureCategory.Timeout
            FollowupGenerationFailureCategory.InferenceError -> FollowupStepFailureCategory.InferenceError
            FollowupGenerationFailureCategory.InvalidModelOutput -> FollowupStepFailureCategory.InvalidModelOutput
        }
}
