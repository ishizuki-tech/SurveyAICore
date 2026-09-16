package com.negi.surveyaicore.evaluation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowupGeneratorTest {
    @Test
    fun makesExactlyOneInferenceCallAndReturnsSuccess() = runBlocking {
        val port = FakePort(FollowupInferenceResult.Success("How much yield did you lose?"))

        val outcome = FollowupGenerator(port).generate(input())

        assertEquals(1, port.calls)
        assertEquals("How much yield did you lose?", (outcome as FollowupGenerationOutcome.Success).question)
    }

    @Test
    fun mapsTimeoutAndInferenceFailuresWithoutRetry() = runBlocking {
        val timeoutPort = FakePort(FollowupInferenceResult.Timeout)
        val errorPort = FakePort(error = IllegalStateException("runtime unavailable"))

        val timeout = FollowupGenerator(timeoutPort).generate(input())
        val error = FollowupGenerator(errorPort).generate(input())

        assertEquals(1, timeoutPort.calls)
        assertEquals(1, errorPort.calls)
        assertEquals(FollowupGenerationFailureCategory.Timeout, (timeout as FollowupGenerationOutcome.Failure).category)
        assertEquals(FollowupGenerationFailureCategory.InferenceError, (error as FollowupGenerationOutcome.Failure).category)
    }

    @Test
    fun mapsInvalidOutputWithoutRetry() = runBlocking {
        val port = FakePort(FollowupInferenceResult.Success("{\"question\":\"How much yield did you lose?\"}"))

        val outcome = FollowupGenerator(port).generate(input())

        assertEquals(1, port.calls)
        assertEquals(FollowupGenerationFailureCategory.InvalidModelOutput, (outcome as FollowupGenerationOutcome.Failure).category)
    }

    @Test
    fun propagatesCancellation() {
        val cancellation = CancellationException("cancelled")
        val port = FakePort(error = cancellation)

        val actual = assertThrows(CancellationException::class.java) { runBlocking { FollowupGenerator(port).generate(input()) } }

        assertSame(cancellation, actual)
        assertEquals(1, port.calls)
    }

    private fun input() =
        FollowupGenerationInput(
            question = "What happened to your crop?",
            expectedAnswerTarget = "State the impact and its cause.",
            originalAnswer = "It was affected.",
            missingPoints = listOf("The impact on yield"),
        )

    private class FakePort(
        private val result: FollowupInferenceResult = FollowupInferenceResult.Success("How much yield did you lose?"),
        private val error: Throwable? = null,
    ) : FollowupInferencePort {
        var calls = 0

        override suspend fun generate(prompt: String): FollowupInferenceResult {
            calls++
            error?.let { throw it }
            return result
        }
    }
}
