package com.negi.surveyaicore

import com.negi.surveyaicore.inference.InferenceOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurveyAICoreResultTest {
    @Test
    fun resultRetainsPublicValueSemantics() {
        val result = SurveyAICoreResult(
            text = "answer",
            durationMs = 42L,
            timedOut = false,
            errorMessage = null,
        )

        assertEquals("answer", result.text)
        assertEquals(42L, result.durationMs)
        assertEquals(false, result.timedOut)
        assertNull(result.errorMessage)
    }

    @Test
    fun internalOutputMapsToStablePublicResult() {
        val output = InferenceOutput(
            rawText = "partial",
            durationMs = 123L,
            timedOut = true,
            error = "timeout",
        )

        assertEquals(
            SurveyAICoreResult(
                text = "partial",
                durationMs = 123L,
                timedOut = true,
                errorMessage = "timeout",
            ),
            output.toSurveyAICoreResult(),
        )
    }
}
