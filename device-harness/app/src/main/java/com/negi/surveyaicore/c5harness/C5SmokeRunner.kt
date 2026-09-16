package com.negi.surveyaicore.c5harness

import android.content.Context
import android.util.Log
import com.negi.surveyaicore.SurveyAICore
import com.negi.surveyaicore.SurveyAICoreAccelerator
import com.negi.surveyaicore.SurveyAICoreConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal object C5SmokeContract {
    const val TAG = "SurveyAICoreC5"
    const val MODEL_FILE_NAME = "model.litertlm"
    const val PROMPT = "Answer in one short sentence: What is the capital of Japan?"

    fun smokeConfig(): SurveyAICoreConfig =
        SurveyAICoreConfig(
            accelerator = SurveyAICoreAccelerator.GPU,
            maxTokens = 512,
            topK = 1,
            topP = 0.0f,
            temperature = 0.0f,
        )
}

internal enum class C5SmokeEvent {
    RUNNER_STARTED,
    CREATE_BEGIN,
    CREATE_SUCCESS,
    GENERATE_BEGIN,
    FIRST_DELTA,
    GENERATE_COMPLETE,
    CLOSE_BEGIN,
    CLOSE_SUCCESS,
    TERMINAL_PASS,
    TERMINAL_FAIL,
}

internal data class C5SmokeReport(
    val createSucceeded: Boolean,
    val deltaCount: Int,
    val text: String,
    val durationMs: Long,
    val timedOut: Boolean,
    val errorMessage: String?,
) {
    val isSuccess: Boolean
        get() = createSucceeded && !timedOut && errorMessage == null && text.isNotBlank()
}

internal class C5SmokeRunner(context: Context) {
    private val appContext = context.applicationContext ?: context

    suspend fun run(
        modelFile: File,
        config: SurveyAICoreConfig,
        prompt: String,
        onEvent: ((C5SmokeEvent) -> Unit)? = null,
    ): C5SmokeReport {
        var core: SurveyAICore? = null
        var primaryFailure: Throwable? = null
        var report: C5SmokeReport? = null
        emit(C5SmokeEvent.RUNNER_STARTED, onEvent)
        Log.i(
            C5SmokeContract.TAG,
            "SMOKE_START modelPath=${modelFile.path} exists=${modelFile.exists()} size=${modelFile.length()}",
        )

        try {
            emit(C5SmokeEvent.CREATE_BEGIN, onEvent)
            Log.i(C5SmokeContract.TAG, "CREATE_BEGIN")
            core = SurveyAICore.create(appContext, modelFile, config)
            emit(C5SmokeEvent.CREATE_SUCCESS, onEvent)
            Log.i(C5SmokeContract.TAG, "CREATE_SUCCESS")

            val deltaCount = AtomicInteger(0)
            val firstDelta = AtomicBoolean(false)
            emit(C5SmokeEvent.GENERATE_BEGIN, onEvent)
            Log.i(C5SmokeContract.TAG, "GENERATE_BEGIN")
            val result =
                core.generate(prompt) {
                    deltaCount.incrementAndGet()
                    if (firstDelta.compareAndSet(false, true)) {
                        emit(C5SmokeEvent.FIRST_DELTA, onEvent)
                        Log.i(C5SmokeContract.TAG, "FIRST_DELTA")
                    }
                }
            val generatedReport =
                C5SmokeReport(
                    createSucceeded = true,
                    deltaCount = deltaCount.get(),
                    text = result.text,
                    durationMs = result.durationMs,
                    timedOut = result.timedOut,
                    errorMessage = result.errorMessage,
                )
            report = generatedReport
            emit(C5SmokeEvent.GENERATE_COMPLETE, onEvent)
            Log.i(
                C5SmokeContract.TAG,
                "GENERATE_COMPLETE deltas=${generatedReport.deltaCount} durationMs=${generatedReport.durationMs} " +
                    "timedOut=${generatedReport.timedOut} errorPresent=${generatedReport.errorMessage != null} " +
                    "textLength=${generatedReport.text.length}",
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            emit(C5SmokeEvent.TERMINAL_FAIL, onEvent)
            Log.e(C5SmokeContract.TAG, "TERMINAL_FAIL", failure)
            throw failure
        } finally {
            core?.let { createdCore ->
                emit(C5SmokeEvent.CLOSE_BEGIN, onEvent)
                Log.i(C5SmokeContract.TAG, "CLOSE_BEGIN")
                withContext(NonCancellable) {
                    try {
                        createdCore.close()
                        emit(C5SmokeEvent.CLOSE_SUCCESS, onEvent)
                        Log.i(C5SmokeContract.TAG, "CLOSE_SUCCESS")
                    } catch (closeFailure: Throwable) {
                        val failure = primaryFailure
                        if (failure != null) {
                            failure.addSuppressed(closeFailure)
                        } else {
                            emit(C5SmokeEvent.TERMINAL_FAIL, onEvent)
                            Log.e(C5SmokeContract.TAG, "TERMINAL_FAIL", closeFailure)
                            throw closeFailure
                        }
                    }
                }
            }
        }

        val completedReport = checkNotNull(report)
        emit(
            if (completedReport.isSuccess) C5SmokeEvent.TERMINAL_PASS else C5SmokeEvent.TERMINAL_FAIL,
            onEvent,
        )
        Log.i(C5SmokeContract.TAG, "TERMINAL_${if (completedReport.isSuccess) "PASS" else "FAIL"}")
        return completedReport
    }

    private fun emit(event: C5SmokeEvent, onEvent: ((C5SmokeEvent) -> Unit)?) {
        try {
            onEvent?.invoke(event)
        } catch (callbackFailure: Throwable) {
            Log.w(C5SmokeContract.TAG, "Diagnostic event callback failed for $event", callbackFailure)
        }
    }
}
