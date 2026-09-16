package com.negi.surveyaicore.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FollowupGenerationInputTest {
    @Test
    fun blankQuestionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { input(question = " ") }
    }

    @Test
    fun blankExpectedTargetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { input(expectedAnswerTarget = " ") }
    }

    @Test
    fun emptyOrBlankMissingPointsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { input(missingPoints = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { input(missingPoints = listOf(" ")) }
    }

    @Test
    fun blankOriginalAnswerIsAccepted() {
        assertEquals("", input(originalAnswer = "").originalAnswer)
    }

    private fun input(
        question: String = "What happened to your crop?",
        expectedAnswerTarget: String = "State the impact and its cause.",
        originalAnswer: String = "It was affected.",
        missingPoints: List<String> = listOf("The impact on yield"),
    ) =
        FollowupGenerationInput(
            question = question,
            expectedAnswerTarget = expectedAnswerTarget,
            originalAnswer = originalAnswer,
            missingPoints = missingPoints,
        )
}
