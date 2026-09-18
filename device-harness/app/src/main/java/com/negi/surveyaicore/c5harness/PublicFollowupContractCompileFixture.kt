package com.negi.surveyaicore.c5harness

import com.negi.surveyaicore.SurveyAIAnsweredFollowup
import com.negi.surveyaicore.SurveyAICompletionPolicy
import com.negi.surveyaicore.SurveyAIFailureCategory
import com.negi.surveyaicore.SurveyAIFollowup
import com.negi.surveyaicore.SurveyAIFollowupOutcome
import com.negi.surveyaicore.SurveyAIFollowupPolicy
import com.negi.surveyaicore.SurveyAIFollowupRequest
import com.negi.surveyaicore.SurveyAIStage
import com.negi.surveyaicore.SurveyAIStopReason

/** Compile-only consumer proof for the release-AAR follow-up contract. */
internal object PublicFollowupContractCompileFixture {
    suspend fun advance(followup: SurveyAIFollowup): String {
        val outcome =
            followup.advance(
                SurveyAIFollowupRequest(
                    question = "What happened to your crop?",
                    expectedAnswerTarget = "State the effect on yield.",
                    originalAnswer = "Yield fell.",
                    answeredFollowups = listOf(SurveyAIAnsweredFollowup("Which crop?", "Maize")),
                    policy =
                        SurveyAIFollowupPolicy(
                            maxFollowups = 1,
                            completion = SurveyAICompletionPolicy(),
                        ),
                ),
            )
        return when (outcome) {
            SurveyAIFollowupOutcome.Completed -> "completed"
            is SurveyAIFollowupOutcome.NeedFollowup -> outcome.question
            is SurveyAIFollowupOutcome.Stopped ->
                when (outcome.reason) {
                    SurveyAIStopReason.FOLLOWUP_CAPACITY_EXHAUSTED -> "stopped"
                }
            is SurveyAIFollowupOutcome.Failure ->
                when (outcome.stage) {
                    SurveyAIStage.EVALUATION,
                    SurveyAIStage.GENERATION,
                    ->
                        when (outcome.category) {
                            SurveyAIFailureCategory.TIMEOUT,
                            SurveyAIFailureCategory.INFERENCE_ERROR,
                            SurveyAIFailureCategory.INVALID_MODEL_OUTPUT,
                            -> "failure"
                        }
                }
        }
    }
}
