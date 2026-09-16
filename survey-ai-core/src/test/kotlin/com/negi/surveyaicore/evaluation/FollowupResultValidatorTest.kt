package com.negi.surveyaicore.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowupResultValidatorTest {
    @Test
    fun acceptsCleanSingleQuestion() {
        assertEquals("How much yield did you lose?", FollowupResultValidator.validate(" How much yield did you lose? ").getOrThrow())
    }

    @Test
    fun acceptsSupportedMultilingualQuestionMarks() {
        assertEquals("収穫量はどのくらい減りましたか？", FollowupResultValidator.validate("収穫量はどのくらい減りましたか？").getOrThrow())
        assertEquals("كم انخفض المحصول؟", FollowupResultValidator.validate("كم انخفض المحصول؟").getOrThrow())
    }

    @Test
    fun rejectsBlankJsonAndFencedOutput() {
        assertInvalid("")
        assertInvalid("{\"followup_question\":\"How much yield did you lose?\"}")
        assertInvalid("```\nHow much yield did you lose?\n```")
    }

    @Test
    fun rejectsExplanatoryPrefixAndMultipleQuestions() {
        assertInvalid("Here is a follow-up question: How much yield did you lose?")
        assertInvalid("How much yield did you lose? What caused it?")
        assertInvalid("収穫量はどのくらい減りましたか？原因は何ですか？")
        assertInvalid("كم انخفض المحصول؟ ما السبب؟")
    }

    @Test
    fun rejectsExcessiveParagraphStyleOutput() {
        assertInvalid("あ".repeat(281) + "？")
        assertInvalid("How much yield did you lose?\nPlease explain why.")
    }

    private fun assertInvalid(rawOutput: String) {
        assertTrue("Expected rejection for: $rawOutput", FollowupResultValidator.validate(rawOutput).isFailure)
    }
}
