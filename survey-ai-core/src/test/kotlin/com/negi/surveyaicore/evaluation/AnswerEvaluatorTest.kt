package com.negi.surveyaicore.evaluation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerEvaluatorTest {
    @Test
    fun performsExactlyOneInferenceAndReturnsComplete() = runBlocking {
        val port = FakePort(EvaluationInferenceResult.Success("{\"score\":90,\"missing_points\":[]}"))

        val outcome = AnswerEvaluator(port).evaluate(input())

        assertEquals(1, port.calls)
        assertTrue(outcome is EvaluationOutcome.Complete)
    }

    @Test
    fun returnsIncompleteWithoutRetry() = runBlocking {
        val port = FakePort(EvaluationInferenceResult.Success("{\"score\":50,\"missing_points\":[\"The cause\"]}"))

        val outcome = AnswerEvaluator(port).evaluate(input())

        assertEquals(1, port.calls)
        assertTrue(outcome is EvaluationOutcome.Incomplete)
    }

    @Test
    fun mapsTimeoutToTypedFailure() = runBlocking {
        val outcome = AnswerEvaluator(FakePort(EvaluationInferenceResult.Timeout)).evaluate(input())

        assertEquals(EvaluationFailureCategory.Timeout, (outcome as EvaluationOutcome.Failure).category)
    }

    @Test
    fun mapsPortFailureAndThrownErrorToInferenceError() = runBlocking {
        val resultFailure = AnswerEvaluator(FakePort(EvaluationInferenceResult.Failure)).evaluate(input())
        val thrownFailure = AnswerEvaluator(FakePort(error = IllegalStateException("runtime unavailable"))).evaluate(input())

        assertEquals(EvaluationFailureCategory.InferenceError, (resultFailure as EvaluationOutcome.Failure).category)
        assertEquals(EvaluationFailureCategory.InferenceError, (thrownFailure as EvaluationOutcome.Failure).category)
    }

    @Test
    fun mapsInvalidModelOutputToTypedFailureWithoutRetry() = runBlocking {
        val port = FakePort(EvaluationInferenceResult.Success("not json"))

        val outcome = AnswerEvaluator(port).evaluate(input())

        assertEquals(1, port.calls)
        assertEquals(EvaluationFailureCategory.InvalidModelOutput, (outcome as EvaluationOutcome.Failure).category)
    }

    @Test
    fun propagatesCancellation() {
        val cancellation = CancellationException("cancelled")
        val port = FakePort(error = cancellation)

        val actual = assertThrows(CancellationException::class.java) { runBlocking { AnswerEvaluator(port).evaluate(input()) } }

        assertSame(cancellation, actual)
        assertEquals(1, port.calls)
    }

    private fun input() =
        EvaluationInput(
            question = "What changed in your crop?",
            expectedAnswerTarget = "State the change and its cause.",
            originalAnswer = "Yield fell because of pests.",
        )

    private class FakePort(
        private val result: EvaluationInferenceResult = EvaluationInferenceResult.Success("{\"score\":90,\"missing_points\":[]}"),
        private val error: Throwable? = null,
    ) : EvaluationInferencePort {
        var calls = 0

        override suspend fun generate(prompt: String): EvaluationInferenceResult {
            calls++
            error?.let { throw it }
            return result
        }
    }
}
