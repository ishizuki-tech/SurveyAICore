package com.negi.surveyaicore.evaluation

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationPolicyTest {
    @Test
    fun scoreBelowThresholdIsIncomplete() {
        assertTrue(EvaluationPolicy(EvaluationPolicyConfig()).apply(AnswerEvaluation(89, emptyList())) is EvaluationOutcome.Incomplete)
    }

    @Test
    fun thresholdAndPerfectScoreCanBeComplete() {
        val policy = EvaluationPolicy(EvaluationPolicyConfig())

        assertTrue(policy.apply(AnswerEvaluation(90, emptyList())) is EvaluationOutcome.Complete)
        assertTrue(policy.apply(AnswerEvaluation(100, emptyList())) is EvaluationOutcome.Complete)
    }

    @Test
    fun missingPointLimitIsInclusive() {
        val policy = EvaluationPolicy(EvaluationPolicyConfig(maxAllowedMissingPoints = 1))

        assertTrue(policy.apply(AnswerEvaluation(90, listOf("One detail"))) is EvaluationOutcome.Complete)
        assertTrue(policy.apply(AnswerEvaluation(90, listOf("One", "Two"))) is EvaluationOutcome.Incomplete)
    }

    @Test
    fun customThresholdAndMissingPointLimitAreApplied() {
        val policy = EvaluationPolicy(EvaluationPolicyConfig(completionScoreThreshold = 75, maxAllowedMissingPoints = 2))

        assertTrue(policy.apply(AnswerEvaluation(75, listOf("One", "Two"))) is EvaluationOutcome.Complete)
        assertTrue(policy.apply(AnswerEvaluation(74, emptyList())) is EvaluationOutcome.Incomplete)
    }

    @Test
    fun invalidConfigurationFailsFast() {
        assertThrows(IllegalArgumentException::class.java) { EvaluationPolicyConfig(completionScoreThreshold = 0) }
        assertThrows(IllegalArgumentException::class.java) { EvaluationPolicyConfig(completionScoreThreshold = 101) }
        assertThrows(IllegalArgumentException::class.java) { EvaluationPolicyConfig(maxAllowedMissingPoints = -1) }
    }
}
