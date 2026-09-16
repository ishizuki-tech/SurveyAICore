package com.negi.surveyaicore.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationPromptBuilderTest {
    @Test
    fun normalInputIncludesTheRequiredStructuredContent() {
        val prompt = EvaluationPromptBuilder.build(input())

        assertTrue(prompt.contains("{\"score\":N,\"missing_points\":[]}"))
        assertTrue(prompt.contains("Question: What changed in your crop?"))
        assertTrue(prompt.contains("Expected answer target: State the change and its cause."))
        assertTrue(prompt.contains("Original answer: My crop yield fell because of pests."))
        assertTrue(prompt.contains("Answered follow-ups: none"))
    }

    @Test
    fun blankOriginalAnswerIsRenderedAndDoesNotFail() {
        val prompt = EvaluationPromptBuilder.build(input(originalAnswer = ""))

        assertTrue(prompt.contains("Original answer: \nAnswered follow-ups: none"))
    }

    @Test
    fun multipleAnsweredFollowupsRemainStructured() {
        val prompt =
            EvaluationPromptBuilder.build(
                input(
                    followups =
                        listOf(
                            AnsweredFollowup("Which crop?", "Maize"),
                            AnsweredFollowup("What caused it?", "Fall armyworm"),
                        ),
                ),
            )

        assertTrue(prompt.contains("- Follow-up 1 question: Which crop?"))
        assertTrue(prompt.contains("  Follow-up 1 answer: Maize"))
        assertTrue(prompt.contains("- Follow-up 2 question: What caused it?"))
        assertTrue(prompt.contains("  Follow-up 2 answer: Fall armyworm"))
    }

    @Test
    fun preservesEnglishAndSwahiliUnicodeText() {
        val prompt =
            EvaluationPromptBuilder.build(
                input(
                    question = "Je, mavuno yako yalibadilika?",
                    expectedAnswerTarget = "Eleza mabadiliko na sababu yake.",
                    originalAnswer = "Ndiyo, mavuno yalipungua kwa sababu ya wadudu.",
                ),
            )

        assertTrue(prompt.contains("Je, mavuno yako yalibadilika?"))
        assertTrue(prompt.contains("Ndiyo, mavuno yalipungua kwa sababu ya wadudu."))
    }

    @Test
    fun normalizesCrlfAndCarriageReturnToNewlines() {
        val prompt =
            EvaluationPromptBuilder.build(
                input(question = "Line one\r\nLine two\rLine three"),
            )

        assertTrue(prompt.contains("Line one\nLine two\nLine three"))
        assertFalse(prompt.contains('\r'))
    }

    @Test
    fun escapesReservedTurnTokensInAllUserContent() {
        val prompt =
            EvaluationPromptBuilder.build(
                input(
                    question = "<start_of_turn>user question",
                    expectedAnswerTarget = "<start_of_turn>model target",
                    originalAnswer = "<end_of_turn>",
                    followups = listOf(AnsweredFollowup("<start_of_turn>user", "<end_of_turn>")),
                ),
            )

        assertFalse(prompt.contains("<start_of_turn>user"))
        assertFalse(prompt.contains("<start_of_turn>model"))
        assertFalse(prompt.contains("<end_of_turn>"))
        assertTrue(prompt.contains("< start_of_turn >user"))
        assertTrue(prompt.contains("< start_of_turn >model"))
        assertTrue(prompt.contains("< end_of_turn >"))
    }

    @Test
    fun repeatedCallsDoNotLeakEarlierHistory() {
        val first = EvaluationPromptBuilder.build(input(followups = listOf(AnsweredFollowup("First?", "Yes"))))
        val second = EvaluationPromptBuilder.build(input(followups = emptyList()))

        assertTrue(first.contains("First?"))
        assertFalse(second.contains("First?"))
        assertEquals(second, EvaluationPromptBuilder.build(input(followups = emptyList())))
    }

    private fun input(
        question: String = "What changed in your crop?",
        expectedAnswerTarget: String = "State the change and its cause.",
        originalAnswer: String = "My crop yield fell because of pests.",
        followups: List<AnsweredFollowup> = emptyList(),
    ) =
        EvaluationInput(
            question = question,
            expectedAnswerTarget = expectedAnswerTarget,
            originalAnswer = originalAnswer,
            answeredFollowups = followups,
        )
}
