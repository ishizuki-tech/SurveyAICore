package com.negi.surveyaicore.usertest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.surveyaicore.SurveyAIAnsweredFollowup
import com.negi.surveyaicore.SurveyAICompletionPolicy
import com.negi.surveyaicore.SurveyAIFollowupOutcome
import com.negi.surveyaicore.SurveyAIFollowupPolicy
import com.negi.surveyaicore.SurveyAIFollowupRequest
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

internal const val USER_TEST_MODEL_FILE_NAME = "model.litertlm"

internal data class UserTestEvent(
    val message: String,
    val elapsedMs: Long? = null,
)

internal data class UserTestUiState(
    val initialized: Boolean = false,
    val busy: Boolean = false,
    val status: String = "Model not initialized",
    val question: String = "",
    val expectedAnswerTarget: String = "",
    val originalAnswer: String = "",
    val answeredFollowups: List<SurveyAIAnsweredFollowup> = emptyList(),
    val outcome: SurveyAIFollowupOutcome? = null,
    val followupAnswer: String = "",
    val lastAdvanceMs: Long? = null,
    val events: List<UserTestEvent> = emptyList(),
)

internal open class UserTestViewModel(
    private val executor: UserTestExecutor,
    private val modelFile: File,
) : ViewModel() {
    private val disposalScope = CoroutineScope(Dispatchers.IO)
    private val mutableState = MutableStateFlow(UserTestUiState())
    val state: StateFlow<UserTestUiState> = mutableState.asStateFlow()

    fun initialize() {
        viewModelScope.launch { initializeNow() }
    }

    suspend fun initializeNow() {
        if (mutableState.value.busy || mutableState.value.initialized) return
        update { it.copy(busy = true, status = "Initializing model") }
        val result = executor.initialize(modelFile)
        update { current ->
            result.fold(
                onSuccess = { current.withEvent("Core initialized").copy(initialized = true, busy = false, status = "Core ready") },
                onFailure = { error -> current.withEvent("Initialization failed").copy(busy = false, status = "Initialization failed: ${error.message}") },
            )
        }
    }

    fun close() {
        viewModelScope.launch { closeNow() }
    }

    suspend fun closeNow() {
        if (mutableState.value.busy) return
        update { it.copy(busy = true, status = "Closing Core") }
        val result = executor.close()
        update { current ->
            result.fold(
                onSuccess = {
                    current.withEvent("Core closed").copy(
                        initialized = false,
                        busy = false,
                        status = "Model not initialized",
                        answeredFollowups = emptyList(),
                        outcome = null,
                        followupAnswer = "",
                        lastAdvanceMs = null,
                    )
                },
                onFailure = { error -> current.withEvent("Close failed").copy(busy = false, status = "Close failed: ${error.message}") },
            )
        }
    }

    fun evaluate() {
        viewModelScope.launch { evaluateNow() }
    }

    suspend fun evaluateNow() {
        val current = mutableState.value
        if (!current.initialized || current.busy) return
        val request = current.requestOrNull() ?: run {
            update { it.copy(status = "Question and expected target are required") }
            return
        }
        update { it.copy(busy = true, status = "Evaluating") }
        val mark = TimeSource.Monotonic.markNow()
        try {
            val outcome = executor.advance(request)
            val elapsed = mark.elapsedNow().inWholeMilliseconds
            update {
                it.withEvent("Advance completed: ${outcome.label()}", elapsed)
                    .copy(busy = false, status = outcome.label(), outcome = outcome, lastAdvanceMs = elapsed, followupAnswer = "")
            }
        } catch (cancelled: CancellationException) {
            update { it.withEvent("Advance cancelled").copy(busy = false, status = "Advance cancelled") }
            throw cancelled
        } catch (error: Throwable) {
            update { it.withEvent("Advance failed").copy(busy = false, status = "Advance failed: ${error.message}") }
        }
    }

    fun submitFollowup() {
        viewModelScope.launch { submitFollowupNow() }
    }

    suspend fun submitFollowupNow() {
        val needFollowup = mutableState.value.outcome as? SurveyAIFollowupOutcome.NeedFollowup ?: return
        val answer = mutableState.value.followupAnswer.trim()
        if (answer.isBlank()) {
            update { it.copy(status = "Follow-up answer is required") }
            return
        }
        update {
            it.copy(
                answeredFollowups = it.answeredFollowups + SurveyAIAnsweredFollowup(needFollowup.question, answer),
                followupAnswer = "",
                outcome = null,
            )
        }
        evaluateNow()
    }

    fun newTest() {
        if (mutableState.value.busy) return
        update { it.withEvent("New test").copy(answeredFollowups = emptyList(), outcome = null, followupAnswer = "", lastAdvanceMs = null, status = "Ready for a new test") }
    }

    fun updateQuestion(value: String) = update { if (it.hasPendingFollowup()) it else it.copy(question = value) }

    fun updateExpectedAnswerTarget(value: String) = update { if (it.hasPendingFollowup()) it else it.copy(expectedAnswerTarget = value) }

    fun updateOriginalAnswer(value: String) = update { if (it.hasPendingFollowup()) it else it.copy(originalAnswer = value) }

    fun updateFollowupAnswer(value: String) = update { it.copy(followupAnswer = value) }

    override fun onCleared() {
        disposalScope.launch {
            withContext(NonCancellable) { executor.close() }
        }
    }

    private fun UserTestUiState.requestOrNull(): SurveyAIFollowupRequest? =
        if (question.isBlank() || expectedAnswerTarget.isBlank()) {
            null
        } else {
            SurveyAIFollowupRequest(
                question = question,
                expectedAnswerTarget = expectedAnswerTarget,
                originalAnswer = originalAnswer,
                answeredFollowups = answeredFollowups,
                policy =
                    SurveyAIFollowupPolicy(
                        maxFollowups = 1,
                        completion = SurveyAICompletionPolicy(scoreThreshold = 90, maxAllowedMissingPoints = 0),
                    ),
            )
        }

    private fun SurveyAIFollowupOutcome.label(): String =
        when (this) {
            SurveyAIFollowupOutcome.Completed -> "Completed"
            is SurveyAIFollowupOutcome.NeedFollowup -> "Need follow-up"
            is SurveyAIFollowupOutcome.Stopped -> "Stopped: ${reason.name}"
            is SurveyAIFollowupOutcome.Failure -> "Failure: ${stage.name} / ${category.name}"
        }

    private fun UserTestUiState.withEvent(message: String, elapsedMs: Long? = null): UserTestUiState =
        copy(events = (events + UserTestEvent(message, elapsedMs)).takeLast(20))

    private fun UserTestUiState.hasPendingFollowup(): Boolean = outcome is SurveyAIFollowupOutcome.NeedFollowup

    private inline fun update(transform: (UserTestUiState) -> UserTestUiState) {
        mutableState.value = transform(mutableState.value)
    }
}

internal class UserTestViewModelFactory(
    private val executor: UserTestExecutor,
    private val modelFile: File,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(modelClass.isAssignableFrom(UserTestViewModel::class.java))
        return UserTestViewModel(executor, modelFile) as T
    }
}
