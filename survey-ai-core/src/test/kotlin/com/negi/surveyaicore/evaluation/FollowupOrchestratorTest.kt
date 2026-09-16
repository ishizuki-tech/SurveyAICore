package com.negi.surveyaicore.evaluation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowupOrchestratorTest {
    @Test
    fun validatesSessionStateAndPolicy() {
        assertThrows(IllegalArgumentException::class.java) {
            FollowupSessionState(question = " ", expectedAnswerTarget = "Target", originalAnswer = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FollowupSessionState(question = "Question", expectedAnswerTarget = " ", originalAnswer = "")
        }
        assertEquals("", state(originalAnswer = "").originalAnswer)
        assertEquals(0, FollowupOrchestrationPolicy(maxFollowups = 0).maxFollowups)
        assertThrows(IllegalArgumentException::class.java) {
            FollowupOrchestrationPolicy(maxFollowups = -1)
        }
    }

    @Test
    fun completeEvaluationReturnsCompletedWithoutGeneration() = runBlocking {
        val evaluationPort = FakeEvaluationPort(listOf(completeResult()))
        val generationPort = FakeGenerationPort(listOf(FollowupInferenceResult.Success("How much yield did you lose?")))

        val outcome = orchestrator(evaluationPort, generationPort).advance(state())

        assertEquals(1, evaluationPort.calls)
        assertEquals(0, generationPort.calls)
        assertEquals(90, (outcome as FollowupStepOutcome.Completed).evaluation.score)
    }

    @Test
    fun incompleteEvaluationWithCapacityGeneratesOneQuestionUsingFullContext() = runBlocking {
        val evaluationPort = FakeEvaluationPort(listOf(incompleteResult("The effect on yield")))
        val generationPort = FakeGenerationPort(listOf(FollowupInferenceResult.Success("How much yield did you lose?")))
        val history = listOf(AnsweredFollowup("Which crop was affected?", "Maize"))

        val outcome = orchestrator(evaluationPort, generationPort, maxFollowups = 2).advance(state(answeredFollowups = history))

        assertEquals(1, evaluationPort.calls)
        assertEquals(1, generationPort.calls)
        assertEquals("How much yield did you lose?", (outcome as FollowupStepOutcome.NeedFollowup).question)
        assertTrue(generationPort.prompts.single().contains("Follow-up 1 question: Which crop was affected?"))
        assertTrue(generationPort.prompts.single().contains("Follow-up 1 answer: Maize"))
        assertTrue(generationPort.prompts.single().contains("Missing information:\n- The effect on yield"))
    }

    @Test
    fun capacityIsDerivedFromAnsweredFollowupsSize() = runBlocking {
        val zeroCapacityEvaluationPort = FakeEvaluationPort(listOf(incompleteResult()))
        val zeroCapacityGenerationPort = FakeGenerationPort(listOf(FollowupInferenceResult.Success("Unused?")))
        val atCapacityEvaluationPort = FakeEvaluationPort(listOf(incompleteResult()))
        val atCapacityGenerationPort = FakeGenerationPort(listOf(FollowupInferenceResult.Success("Unused?")))

        val zeroCapacityOutcome = orchestrator(zeroCapacityEvaluationPort, zeroCapacityGenerationPort, maxFollowups = 0).advance(state())
        val atCapacityOutcome =
            orchestrator(atCapacityEvaluationPort, atCapacityGenerationPort, maxFollowups = 1).advance(
                state(answeredFollowups = listOf(AnsweredFollowup("What changed?", "Yield fell"))),
            )

        assertEquals(StopReason.FollowupCapacityExhausted, (zeroCapacityOutcome as FollowupStepOutcome.Stopped).reason)
        assertEquals(StopReason.FollowupCapacityExhausted, (atCapacityOutcome as FollowupStepOutcome.Stopped).reason)
        assertEquals(1, zeroCapacityEvaluationPort.calls)
        assertEquals(1, atCapacityEvaluationPort.calls)
        assertEquals(0, zeroCapacityGenerationPort.calls)
        assertEquals(0, atCapacityGenerationPort.calls)
    }

    @Test
    fun mapsEvaluatorFailuresWithoutGeneration() = runBlocking {
        assertEvaluationFailure(EvaluationInferenceResult.Timeout, FollowupStepFailureCategory.Timeout)
        assertEvaluationFailure(EvaluationInferenceResult.Failure, FollowupStepFailureCategory.InferenceError)
        assertEvaluationFailure(EvaluationInferenceResult.Success("not json"), FollowupStepFailureCategory.InvalidModelOutput)
    }

    @Test
    fun mapsGeneratorFailuresAfterOneIncompleteEvaluation() = runBlocking {
        assertGenerationFailure(FollowupInferenceResult.Timeout, FollowupStepFailureCategory.Timeout)
        assertGenerationFailure(FollowupInferenceResult.Failure, FollowupStepFailureCategory.InferenceError)
        assertGenerationFailure(
            FollowupInferenceResult.Success("not a question"),
            FollowupStepFailureCategory.InvalidModelOutput,
        )
    }

    @Test
    fun propagatesEvaluatorCancellationWithoutGeneration() {
        val cancellation = CancellationException("cancelled")
        val evaluationPort = FakeEvaluationPort(listOf(completeResult()), error = cancellation)
        val generationPort = FakeGenerationPort(listOf(FollowupInferenceResult.Success("Unused?")))

        val actual = assertThrows(CancellationException::class.java) {
            runBlocking { orchestrator(evaluationPort, generationPort).advance(state()) }
        }

        assertSame(cancellation, actual)
        assertEquals(1, evaluationPort.calls)
        assertEquals(0, generationPort.calls)
    }

    @Test
    fun propagatesGeneratorCancellation() {
        val cancellation = CancellationException("cancelled")
        val evaluationPort = FakeEvaluationPort(listOf(incompleteResult()))
        val generationPort = FakeGenerationPort(listOf(FollowupInferenceResult.Success("Unused?")), error = cancellation)

        val actual = assertThrows(CancellationException::class.java) {
            runBlocking { orchestrator(evaluationPort, generationPort).advance(state()) }
        }

        assertSame(cancellation, actual)
        assertEquals(1, evaluationPort.calls)
        assertEquals(1, generationPort.calls)
    }

    @Test
    fun laterAdvanceWithAppendedHistoryPerformsFreshEvaluationWithoutLooping() = runBlocking {
        val evaluationPort = FakeEvaluationPort(listOf(incompleteResult(), completeResult()))
        val generationPort = FakeGenerationPort(listOf(FollowupInferenceResult.Success("How much yield did you lose?")))
        val orchestration = orchestrator(evaluationPort, generationPort, maxFollowups = 1)

        val first = orchestration.advance(state())
        val second =
            orchestration.advance(
                state(answeredFollowups = listOf(AnsweredFollowup("How much yield did you lose?", "Half"))),
            )

        assertTrue(first is FollowupStepOutcome.NeedFollowup)
        assertTrue(second is FollowupStepOutcome.Completed)
        assertEquals(2, evaluationPort.calls)
        assertEquals(1, generationPort.calls)
        assertTrue(evaluationPort.prompts[1].contains("Follow-up 1 answer: Half"))
    }

    private suspend fun assertEvaluationFailure(
        result: EvaluationInferenceResult,
        expectedCategory: FollowupStepFailureCategory,
    ) {
        val evaluationPort = FakeEvaluationPort(listOf(result))
        val generationPort = FakeGenerationPort(listOf(FollowupInferenceResult.Success("Unused?")))

        val outcome = orchestrator(evaluationPort, generationPort).advance(state())

        assertFailure(outcome, FollowupFailureStage.Evaluation, expectedCategory)
        assertEquals(1, evaluationPort.calls)
        assertEquals(0, generationPort.calls)
    }

    private suspend fun assertGenerationFailure(
        result: FollowupInferenceResult,
        expectedCategory: FollowupStepFailureCategory,
    ) {
        val evaluationPort = FakeEvaluationPort(listOf(incompleteResult()))
        val generationPort = FakeGenerationPort(listOf(result))

        val outcome = orchestrator(evaluationPort, generationPort).advance(state())

        assertFailure(outcome, FollowupFailureStage.Generation, expectedCategory)
        assertEquals(1, evaluationPort.calls)
        assertEquals(1, generationPort.calls)
    }

    private fun assertFailure(
        outcome: FollowupStepOutcome,
        expectedStage: FollowupFailureStage,
        expectedCategory: FollowupStepFailureCategory,
    ) {
        val failure = outcome as FollowupStepOutcome.Failure
        assertEquals(expectedStage, failure.stage)
        assertEquals(expectedCategory, failure.category)
    }

    private fun orchestrator(
        evaluationPort: FakeEvaluationPort,
        generationPort: FakeGenerationPort,
        maxFollowups: Int = 1,
    ) =
        FollowupOrchestrator(
            evaluator = AnswerEvaluator(evaluationPort),
            generator = FollowupGenerator(generationPort),
            policy = FollowupOrchestrationPolicy(maxFollowups),
        )

    private fun state(
        originalAnswer: String = "Yield fell.",
        answeredFollowups: List<AnsweredFollowup> = emptyList(),
    ) =
        FollowupSessionState(
            question = "What happened to your crop?",
            expectedAnswerTarget = "State the effect on yield.",
            originalAnswer = originalAnswer,
            answeredFollowups = answeredFollowups,
        )

    private fun completeResult() = EvaluationInferenceResult.Success("{\"score\":90,\"missing_points\":[]}")

    private fun incompleteResult(missingPoint: String = "The effect on yield") =
        EvaluationInferenceResult.Success("{\"score\":50,\"missing_points\":[\"$missingPoint\"]}")

    private class FakeEvaluationPort(
        private val results: List<EvaluationInferenceResult>,
        private val error: Throwable? = null,
    ) : EvaluationInferencePort {
        var calls = 0
        val prompts = mutableListOf<String>()

        override suspend fun generate(prompt: String): EvaluationInferenceResult {
            prompts += prompt
            calls++
            error?.let { throw it }
            return results[calls - 1]
        }
    }

    private class FakeGenerationPort(
        private val results: List<FollowupInferenceResult>,
        private val error: Throwable? = null,
    ) : FollowupInferencePort {
        var calls = 0
        val prompts = mutableListOf<String>()

        override suspend fun generate(prompt: String): FollowupInferenceResult {
            prompts += prompt
            calls++
            error?.let { throw it }
            return results[calls - 1]
        }
    }
}
