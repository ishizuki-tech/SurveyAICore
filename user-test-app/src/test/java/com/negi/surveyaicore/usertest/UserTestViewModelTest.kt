package com.negi.surveyaicore.usertest

import com.negi.surveyaicore.SurveyAIFailureCategory
import com.negi.surveyaicore.SurveyAIFollowupOutcome
import com.negi.surveyaicore.SurveyAIFollowupRequest
import com.negi.surveyaicore.SurveyAIStage
import com.negi.surveyaicore.SurveyAIStopReason
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTestViewModelTest {
    @Test
    fun completedIsRenderedAfterOneAdvance() = runBlocking {
        val executor = FakeExecutor(listOf(SurveyAIFollowupOutcome.Completed))
        val viewModel = readyViewModel(executor)

        viewModel.evaluateNow()

        assertEquals(SurveyAIFollowupOutcome.Completed, viewModel.state.value.outcome)
        assertEquals(1, executor.advanceCalls)
    }

    @Test
    fun needFollowupIsPreservedForTheUser() = runBlocking {
        val executor = FakeExecutor(listOf(SurveyAIFollowupOutcome.NeedFollowup("How much yield was lost?")))
        val viewModel = readyViewModel(executor)

        viewModel.evaluateNow()

        assertEquals("How much yield was lost?", (viewModel.state.value.outcome as SurveyAIFollowupOutcome.NeedFollowup).question)
    }

    @Test
    fun submittedFollowupIsIncludedInSecondAdvance() = runBlocking {
        val executor = FakeExecutor(listOf(SurveyAIFollowupOutcome.NeedFollowup("How much yield was lost?"), SurveyAIFollowupOutcome.Completed))
        val viewModel = readyViewModel(executor)

        viewModel.evaluateNow()
        viewModel.updateFollowupAnswer("Half")
        viewModel.submitFollowupNow()

        assertEquals(SurveyAIFollowupOutcome.Completed, viewModel.state.value.outcome)
        assertEquals("Half", executor.requests[1].answeredFollowups.single().answer)
        assertEquals(2, executor.advanceCalls)
    }

    @Test
    fun pendingFollowupLocksSourceContextUntilSubmission() = runBlocking {
        val executor =
            FakeExecutor(
                listOf(
                    SurveyAIFollowupOutcome.NeedFollowup("How much yield was lost?"),
                    SurveyAIFollowupOutcome.Completed,
                ),
            )
        val viewModel = readyViewModel(executor)

        viewModel.evaluateNow()
        viewModel.updateQuestion("A different question")
        viewModel.updateExpectedAnswerTarget("A different target")
        viewModel.updateOriginalAnswer("A different answer")
        viewModel.updateFollowupAnswer("Half")
        viewModel.submitFollowupNow()

        val secondRequest = executor.requests[1]
        assertEquals("What happened to your crop?", secondRequest.question)
        assertEquals("State the effect on yield.", secondRequest.expectedAnswerTarget)
        assertEquals("Yield fell.", secondRequest.originalAnswer)
        assertEquals("How much yield was lost?", secondRequest.answeredFollowups.single().question)
    }

    @Test
    fun stoppedIsRenderedWithoutRetry() = runBlocking {
        val executor = FakeExecutor(listOf(SurveyAIFollowupOutcome.Stopped(SurveyAIStopReason.FOLLOWUP_CAPACITY_EXHAUSTED)))
        val viewModel = readyViewModel(executor)

        viewModel.evaluateNow()

        assertEquals(SurveyAIFollowupOutcome.Stopped(SurveyAIStopReason.FOLLOWUP_CAPACITY_EXHAUSTED), viewModel.state.value.outcome)
        assertEquals(1, executor.advanceCalls)
    }

    @Test
    fun failureIsRenderedWithoutAutomaticRetry() = runBlocking {
        val executor = FakeExecutor(listOf(SurveyAIFollowupOutcome.Failure(SurveyAIStage.EVALUATION, SurveyAIFailureCategory.TIMEOUT)))
        val viewModel = readyViewModel(executor)

        viewModel.evaluateNow()

        assertEquals(SurveyAIFollowupOutcome.Failure(SurveyAIStage.EVALUATION, SurveyAIFailureCategory.TIMEOUT), viewModel.state.value.outcome)
        assertEquals(1, executor.advanceCalls)
    }

    @Test
    fun newTestClearsAnsweredHistory() = runBlocking {
        val executor =
            FakeExecutor(
                listOf(
                    SurveyAIFollowupOutcome.NeedFollowup("How much yield was lost?"),
                    SurveyAIFollowupOutcome.Completed,
                ),
            )
        val viewModel = readyViewModel(executor)

        viewModel.evaluateNow()
        viewModel.updateFollowupAnswer("Half")
        viewModel.submitFollowupNow()
        viewModel.newTest()

        assertTrue(viewModel.state.value.answeredFollowups.isEmpty())
        assertEquals(null, viewModel.state.value.outcome)
    }

    @Test
    fun disposalClosesTheOwnedSession() = runBlocking {
        val executor = FakeExecutor(listOf(SurveyAIFollowupOutcome.Completed))
        val viewModel = DisposableTestViewModel(executor, File("model.litertlm"))
        viewModel.updateQuestion("What happened to your crop?")
        viewModel.updateExpectedAnswerTarget("State the effect on yield.")
        viewModel.updateOriginalAnswer("Yield fell.")
        viewModel.initializeNow()

        viewModel.disposeForTest()

        withTimeout(5_000) { executor.closeObserved.await() }
        assertEquals(1, executor.closeCalls)
    }

    private suspend fun readyViewModel(executor: FakeExecutor): UserTestViewModel =
        UserTestViewModel(executor, File("model.litertlm")).also {
            it.updateQuestion("What happened to your crop?")
            it.updateExpectedAnswerTarget("State the effect on yield.")
            it.updateOriginalAnswer("Yield fell.")
            it.initializeNow()
        }

    private class FakeExecutor(
        private val outcomes: List<SurveyAIFollowupOutcome>,
    ) : UserTestExecutor {
        var advanceCalls = 0
        var closeCalls = 0
        val requests = mutableListOf<SurveyAIFollowupRequest>()
        val closeObserved = CompletableDeferred<Unit>()

        override suspend fun initialize(modelFile: File): Result<Unit> = Result.success(Unit)

        override suspend fun advance(request: SurveyAIFollowupRequest): SurveyAIFollowupOutcome {
            requests += request
            return outcomes[advanceCalls++]
        }

        override suspend fun close(): Result<Unit> {
            closeCalls++
            closeObserved.complete(Unit)
            return Result.success(Unit)
        }
    }

    private class DisposableTestViewModel(
        executor: UserTestExecutor,
        modelFile: File,
    ) : UserTestViewModel(executor, modelFile) {
        fun disposeForTest() {
            super.onCleared()
        }
    }
}
