package com.negi.surveyaicore

import com.negi.surveyaicore.inference.InferenceClient
import com.negi.surveyaicore.inference.InferenceOutput
import com.negi.surveyaicore.runtime.RuntimeModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyAIFollowupTest {
    @Test
    fun mapsCompleteWithoutGeneration() = runBlocking {
        val client = FakeClient(outputs = mutableListOf(success("{\"score\":90,\"missing_points\":[]}")))

        val outcome = facade(client).advance(request())

        assertEquals(SurveyAIFollowupOutcome.Completed, outcome)
        assertEquals(1, client.calls)
    }

    @Test
    fun mapsNeedFollowupAndAnsweredHistory() = runBlocking {
        val client = FakeClient(
            outputs = mutableListOf(
                success("{\"score\":50,\"missing_points\":[\"yield loss\"]}"),
                success("How much yield did you lose?"),
            ),
        )

        val outcome = facade(client).advance(
            request(answered = listOf(SurveyAIAnsweredFollowup("Which crop?", "Maize")), maxFollowups = 2),
        )

        assertEquals(SurveyAIFollowupOutcome.NeedFollowup("How much yield did you lose?"), outcome)
        assertEquals(2, client.calls)
        assertTrue(client.prompts[1].contains("Follow-up 1 answer: Maize"))
    }

    @Test
    fun mapsCapacityExhaustedWithoutGeneration() = runBlocking {
        val client = FakeClient(outputs = mutableListOf(success("{\"score\":50,\"missing_points\":[\"yield loss\"]}")))

        val outcome = facade(client).advance(request(maxFollowups = 1, answered = listOf(SurveyAIAnsweredFollowup("What changed?", "Yield fell"))))

        assertEquals(SurveyAIFollowupOutcome.Stopped(SurveyAIStopReason.FOLLOWUP_CAPACITY_EXHAUSTED), outcome)
        assertEquals(1, client.calls)
    }

    @Test
    fun mapsEvaluationFailuresWithoutRetry() = runBlocking {
        assertFailure(
            outputs = mutableListOf(InferenceOutput("", 1, timedOut = true)),
            expected = SurveyAIFollowupOutcome.Failure(SurveyAIStage.EVALUATION, SurveyAIFailureCategory.TIMEOUT),
            expectedCalls = 1,
        )
        assertFailure(
            outputs = mutableListOf(InferenceOutput("", 1, error = "failure")),
            expected = SurveyAIFollowupOutcome.Failure(SurveyAIStage.EVALUATION, SurveyAIFailureCategory.INFERENCE_ERROR),
            expectedCalls = 1,
        )
        assertFailure(
            outputs = mutableListOf(success("not json")),
            expected = SurveyAIFollowupOutcome.Failure(SurveyAIStage.EVALUATION, SurveyAIFailureCategory.INVALID_MODEL_OUTPUT),
            expectedCalls = 1,
        )
    }

    @Test
    fun mapsGenerationFailuresWithoutRetry() = runBlocking {
        val incomplete = success("{\"score\":50,\"missing_points\":[\"yield loss\"]}")
        assertFailure(
            outputs = mutableListOf(incomplete, InferenceOutput("", 1, timedOut = true)),
            expected = SurveyAIFollowupOutcome.Failure(SurveyAIStage.GENERATION, SurveyAIFailureCategory.TIMEOUT),
            expectedCalls = 2,
        )
        assertFailure(
            outputs = mutableListOf(incomplete, InferenceOutput("", 1, error = "failure")),
            expected = SurveyAIFollowupOutcome.Failure(SurveyAIStage.GENERATION, SurveyAIFailureCategory.INFERENCE_ERROR),
            expectedCalls = 2,
        )
        assertFailure(
            outputs = mutableListOf(incomplete, success("not a question")),
            expected = SurveyAIFollowupOutcome.Failure(SurveyAIStage.GENERATION, SurveyAIFailureCategory.INVALID_MODEL_OUTPUT),
            expectedCalls = 2,
        )
    }

    @Test
    fun mapsCompletionPolicy() = runBlocking {
        val client = FakeClient(outputs = mutableListOf(success("{\"score\":80,\"missing_points\":[]}")))

        val outcome = facade(client).advance(request(scoreThreshold = 80))

        assertEquals(SurveyAIFollowupOutcome.Completed, outcome)
        assertEquals(1, client.calls)
    }

    @Test
    fun mapsAllowedMissingPointsCompletionPolicy() = runBlocking {
        val client =
            FakeClient(
                outputs =
                    mutableListOf(
                        success("{\"score\":90,\"missing_points\":[\"one unresolved detail\"]}"),
                    ),
            )

        val outcome =
            facade(client).advance(
                request(
                    scoreThreshold = 90,
                    maxAllowedMissingPoints = 1,
                ),
            )

        assertEquals(SurveyAIFollowupOutcome.Completed, outcome)
        assertEquals(1, client.calls)
    }

    @Test
    fun propagatesCancellation() {
        val cancellation = CancellationException("cancelled")
        val client = FakeClient(outputs = mutableListOf(success("unused")), failure = cancellation)

        val actual = assertThrows(CancellationException::class.java) { runBlocking { facade(client).advance(request()) } }

        assertSame(cancellation, actual)
        assertEquals(1, client.calls)
    }

    @Test
    fun facadeDoesNotRetainPriorRequestState() = runBlocking {
        val client = FakeClient(
            outputs = mutableListOf(
                success("{\"score\":50,\"missing_points\":[\"yield loss\"]}"),
                success("How much yield did you lose?"),
                success("{\"score\":90,\"missing_points\":[]}"),
            ),
        )
        val facade = facade(client)

        assertTrue(facade.advance(request()) is SurveyAIFollowupOutcome.NeedFollowup)
        assertEquals(SurveyAIFollowupOutcome.Completed, facade.advance(request(originalAnswer = "Complete answer")))
        assertEquals(3, client.calls)
    }

    private suspend fun assertFailure(
        outputs: MutableList<InferenceOutput>,
        expected: SurveyAIFollowupOutcome.Failure,
        expectedCalls: Int,
    ) {
        val client = FakeClient(outputs = outputs)
        assertEquals(expected, facade(client).advance(request()))
        assertEquals(expectedCalls, client.calls)
    }

    private fun facade(client: FakeClient): SurveyAIFollowup =
        SurveyAIFollowup.from(SurveyAICore.createForTesting(client, RuntimeModel("test", "/tmp/test.model")))

    private fun request(
        originalAnswer: String = "Yield fell.",
        answered: List<SurveyAIAnsweredFollowup> = emptyList(),
        maxFollowups: Int = 1,
        scoreThreshold: Int = 90,
        maxAllowedMissingPoints: Int = 0,
    ) =
        SurveyAIFollowupRequest(
            question = "What happened to your crop?",
            expectedAnswerTarget = "State the effect on yield.",
            originalAnswer = originalAnswer,
            answeredFollowups = answered,
            policy = SurveyAIFollowupPolicy(maxFollowups, SurveyAICompletionPolicy(scoreThreshold, maxAllowedMissingPoints)),
        )

    private fun success(raw: String) = InferenceOutput(rawText = raw, durationMs = 1)

    private class FakeClient(
        private val outputs: MutableList<InferenceOutput>,
        private val failure: Throwable? = null,
    ) : InferenceClient {
        var calls = 0
        val prompts = mutableListOf<String>()

        override suspend fun initialize(model: RuntimeModel): Result<Unit> = Result.success(Unit)

        override suspend fun generate(model: RuntimeModel, prompt: String, onDelta: (String) -> Unit): InferenceOutput {
            calls++
            prompts += prompt
            failure?.let { throw it }
            return outputs.removeAt(0)
        }

        override suspend fun reset(model: RuntimeModel): Result<Unit> = Result.success(Unit)

        override suspend fun close(model: RuntimeModel?) = Unit
    }
}
