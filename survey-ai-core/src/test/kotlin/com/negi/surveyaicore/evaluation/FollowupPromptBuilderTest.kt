package com.negi.surveyaicore.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowupPromptBuilderTest {
    @Test
    fun promptIsDeterministicAndIncludesAllContext() {
        val input = input()
        val prompt = FollowupPromptBuilder.build(input)

        assertEquals(prompt, FollowupPromptBuilder.build(input))
        assertTrue(prompt.contains("Survey question: What happened to your crop?"))
        assertTrue(prompt.contains("Expected answer target: State the impact and its cause."))
        assertTrue(prompt.contains("Original answer: It was affected."))
        assertTrue(prompt.contains("- Follow-up 1 question: What crop was affected?"))
        assertTrue(prompt.contains("  Follow-up 1 answer: Maize"))
        assertTrue(prompt.contains("- The impact on yield"))
        assertTrue(prompt.contains("- The cause"))
    }

    @Test
    fun blankOriginalAnswerIsIncludedAndEmptyHistoryIsExplicit() {
        val prompt = FollowupPromptBuilder.build(input(originalAnswer = "", answeredFollowups = emptyList()))

        assertTrue(prompt.contains("Original answer: \nPrevious answered follow-ups: none"))
    }

    @Test
    fun normalizesNewlinesAndEscapesReservedTurnTokens() {
        val prompt =
            FollowupPromptBuilder.build(
                input(
                    question = "Line one\r\n<start_of_turn>user",
                    expectedAnswerTarget = "<start_of_turn>model",
                    originalAnswer = "<end_of_turn>",
                    missingPoints = listOf("Line two\r<end_of_turn>"),
                ),
            )

        assertFalse(prompt.contains('\r'))
        assertFalse(prompt.contains("<start_of_turn>user"))
        assertFalse(prompt.contains("<start_of_turn>model"))
        assertFalse(prompt.contains("<end_of_turn>"))
        assertTrue(prompt.contains("< start_of_turn >user"))
        assertTrue(prompt.contains("< start_of_turn >model"))
        assertTrue(prompt.contains("< end_of_turn >"))
    }

    private fun input(
        question: String = "What happened to your crop?",
        expectedAnswerTarget: String = "State the impact and its cause.",
        originalAnswer: String = "It was affected.",
        answeredFollowups: List<AnsweredFollowup> = listOf(AnsweredFollowup("What crop was affected?", "Maize")),
        missingPoints: List<String> = listOf("The impact on yield", "The cause"),
    ) =
        FollowupGenerationInput(
            question = question,
            expectedAnswerTarget = expectedAnswerTarget,
            originalAnswer = originalAnswer,
            answeredFollowups = answeredFollowups,
            missingPoints = missingPoints,
        )
}
