package com.negi.surveyaicore

import com.negi.surveyaicore.evaluation.AnswerEvaluator
import com.negi.surveyaicore.evaluation.AnsweredFollowup
import com.negi.surveyaicore.evaluation.EvaluationPolicyConfig
import com.negi.surveyaicore.evaluation.FollowupGenerator
import com.negi.surveyaicore.evaluation.FollowupOrchestrationPolicy
import com.negi.surveyaicore.evaluation.FollowupOrchestrator
import com.negi.surveyaicore.evaluation.FollowupSessionState
import com.negi.surveyaicore.evaluation.FollowupStepFailureCategory
import com.negi.surveyaicore.evaluation.FollowupStepOutcome
import com.negi.surveyaicore.evaluation.FollowupFailureStage
import com.negi.surveyaicore.evaluation.StopReason
import com.negi.surveyaicore.evaluation.SurveyAICoreEvaluationInferencePort
import com.negi.surveyaicore.evaluation.SurveyAICoreFollowupInferencePort

/** Stateless follow-up workflow facade backed by an application-owned [SurveyAICore]. */
class SurveyAIFollowup private constructor(
    private val core: SurveyAICore,
) {
    companion object {
        /** Creates a facade that never takes ownership of [core] or calls its close method. */
        fun from(core: SurveyAICore): SurveyAIFollowup = SurveyAIFollowup(core)
    }

    /**
     * Evaluates this immutable request and, when needed and allowed, generates one follow-up.
     * The caller owns persistence and supplies answered follow-ups again on every invocation.
     */
    suspend fun advance(request: SurveyAIFollowupRequest): SurveyAIFollowupOutcome {
        val orchestrator =
            FollowupOrchestrator(
                evaluator = AnswerEvaluator(SurveyAICoreEvaluationInferencePort(core)),
                generator = FollowupGenerator(SurveyAICoreFollowupInferencePort(core)),
                policy = FollowupOrchestrationPolicy(request.policy.maxFollowups),
                evaluationPolicyConfig =
                    EvaluationPolicyConfig(
                        completionScoreThreshold = request.policy.completion.scoreThreshold,
                        maxAllowedMissingPoints = request.policy.completion.maxAllowedMissingPoints,
                    ),
            )
        return orchestrator.advance(request.toInternalState()).toPublicOutcome()
    }
}

data class SurveyAIFollowupRequest(
    val question: String,
    val expectedAnswerTarget: String,
    val originalAnswer: String,
    val answeredFollowups: List<SurveyAIAnsweredFollowup> = emptyList(),
    val policy: SurveyAIFollowupPolicy,
) {
    init {
        require(question.isNotBlank()) { "question must not be blank" }
        require(expectedAnswerTarget.isNotBlank()) { "expectedAnswerTarget must not be blank" }
    }
}

data class SurveyAIAnsweredFollowup(
    val question: String,
    val answer: String,
) {
    init {
        require(question.isNotBlank()) { "follow-up question must not be blank" }
        require(answer.isNotBlank()) { "follow-up answer must not be blank" }
    }
}

data class SurveyAIFollowupPolicy(
    val maxFollowups: Int,
    val completion: SurveyAICompletionPolicy = SurveyAICompletionPolicy(),
) {
    init {
        require(maxFollowups >= 0) { "maxFollowups must be at least 0" }
    }
}

data class SurveyAICompletionPolicy(
    val scoreThreshold: Int = 90,
    val maxAllowedMissingPoints: Int = 0,
) {
    init {
        require(scoreThreshold in 1..100) { "scoreThreshold must be in 1..100" }
        require(maxAllowedMissingPoints >= 0) { "maxAllowedMissingPoints must be at least 0" }
    }
}

sealed interface SurveyAIFollowupOutcome {
    data object Completed : SurveyAIFollowupOutcome

    data class NeedFollowup(
        val question: String,
    ) : SurveyAIFollowupOutcome

    data class Stopped(
        val reason: SurveyAIStopReason,
    ) : SurveyAIFollowupOutcome

    data class Failure(
        val stage: SurveyAIStage,
        val category: SurveyAIFailureCategory,
    ) : SurveyAIFollowupOutcome
}

enum class SurveyAIStopReason {
    FOLLOWUP_CAPACITY_EXHAUSTED,
}

enum class SurveyAIStage {
    EVALUATION,
    GENERATION,
}

enum class SurveyAIFailureCategory {
    TIMEOUT,
    INFERENCE_ERROR,
    INVALID_MODEL_OUTPUT,
}

private fun SurveyAIFollowupRequest.toInternalState(): FollowupSessionState =
    FollowupSessionState(
        question = question,
        expectedAnswerTarget = expectedAnswerTarget,
        originalAnswer = originalAnswer,
        answeredFollowups = answeredFollowups.map { AnsweredFollowup(it.question, it.answer) },
    )

private fun FollowupStepOutcome.toPublicOutcome(): SurveyAIFollowupOutcome =
    when (this) {
        is FollowupStepOutcome.Completed -> SurveyAIFollowupOutcome.Completed
        is FollowupStepOutcome.NeedFollowup -> SurveyAIFollowupOutcome.NeedFollowup(question)
        is FollowupStepOutcome.Stopped ->
            SurveyAIFollowupOutcome.Stopped(
                when (reason) {
                    StopReason.FollowupCapacityExhausted -> SurveyAIStopReason.FOLLOWUP_CAPACITY_EXHAUSTED
                },
            )
        is FollowupStepOutcome.Failure ->
            SurveyAIFollowupOutcome.Failure(
                stage =
                    when (stage) {
                        FollowupFailureStage.Evaluation -> SurveyAIStage.EVALUATION
                        FollowupFailureStage.Generation -> SurveyAIStage.GENERATION
                    },
                category =
                    when (category) {
                        FollowupStepFailureCategory.Timeout -> SurveyAIFailureCategory.TIMEOUT
                        FollowupStepFailureCategory.InferenceError -> SurveyAIFailureCategory.INFERENCE_ERROR
                        FollowupStepFailureCategory.InvalidModelOutput -> SurveyAIFailureCategory.INVALID_MODEL_OUTPUT
                    },
            )
    }
