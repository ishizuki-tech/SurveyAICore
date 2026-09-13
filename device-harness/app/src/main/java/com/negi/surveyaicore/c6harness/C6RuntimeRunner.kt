package com.negi.surveyaicore.c6harness

import android.content.Context
import android.util.Log
import com.negi.surveyaicore.SurveyAICore
import com.negi.surveyaicore.SurveyAICoreConfig
import com.negi.surveyaicore.SurveyAICoreResult
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async

internal enum class C6CancellationTrigger {
    NONE,
    IMMEDIATE,
    FIRST_DELTA,
    AFTER_DELTA_COUNT,
}

internal data class C6CancellationPlan(
    val trigger: C6CancellationTrigger = C6CancellationTrigger.NONE,
    val deltaCount: Int = 0,
)

internal enum class C6RequestTerminalState {
    COMPLETED,
    CANCELLED,
    FAILED,
}

internal data class C6RequestResult(
    val requestId: Long,
    val deltaCount: Int,
    val firstDeltaObserved: Boolean,
    val text: String,
    val durationMs: Long,
    val timedOut: Boolean,
    val errorMessage: String?,
    val cancelRequested: Boolean,
    val cancelObserved: Boolean,
    val terminalState: C6RequestTerminalState,
)

/** Controls only a future test-owned coroutine; it has no runtime backdoor. */
internal class C6RequestControl internal constructor(
    private val recorder: C6EventRecorder,
    private val instanceId: String,
    private val requestId: Long,
    private val accelerator: String,
) {
    private val target = AtomicReference<Job?>(null)
    private val cancellationRequested = AtomicBoolean(false)

    fun attach(job: Job) {
        check(target.compareAndSet(null, job)) { "C6 request control already has a target job" }
    }

    fun cancelRequested(): Boolean = cancellationRequested.get()

    fun requestCancel(reason: String): Boolean {
        if (!cancellationRequested.compareAndSet(false, true)) return false
        recordSafely(C6EventType.CANCEL_REQUESTED, reason)
        target.get()?.cancel(CancellationException("C6 requested cancellation: $reason"))
        return true
    }

    private fun recordSafely(
        type: C6EventType,
        detail: String? = null,
    ) {
        runCatching {
            recorder.record(
                type = type,
                instanceId = instanceId,
                requestId = requestId,
                accelerator = accelerator,
                detail = detail,
            )
        }.onFailure { failure ->
            Log.w(C6EvidenceContract.TAG, "C6 callback bookkeeping failed for $type", failure)
        }
    }
}

internal class C6OwnedRequest(
    val control: C6RequestControl,
    val result: Deferred<C6RequestResult>,
)

/** Public-API-only orchestration primitives for later C6 scenario slices. */
internal class C6RuntimeRunner(
    context: Context,
    private val recorder: C6EventRecorder,
) {
    private val appContext = context.applicationContext ?: context

    suspend fun create(
        instanceId: String,
        modelFile: File,
        config: SurveyAICoreConfig,
    ): C6RuntimeInstance {
        val accelerator = config.accelerator.name
        recorder.record(
            type = C6EventType.MODEL_PRECHECK,
            instanceId = instanceId,
            accelerator = accelerator,
            detail = "exists=${modelFile.isFile} size=${modelFile.length()}",
        )
        require(modelFile.isFile) { "C6 model is missing at ${modelFile.path}" }
        recorder.record(C6EventType.CREATE_BEGIN, instanceId = instanceId, accelerator = accelerator)
        return try {
            C6RuntimeInstance(
                instanceId = instanceId,
                accelerator = accelerator,
                core = SurveyAICore.create(appContext, modelFile, config),
                recorder = recorder,
            ).also {
                recorder.record(C6EventType.CREATE_SUCCESS, instanceId = instanceId, accelerator = accelerator)
            }
        } catch (failure: Throwable) {
            recorder.record(
                C6EventType.CREATE_FAILURE,
                instanceId = instanceId,
                accelerator = accelerator,
                detail = failure.javaClass.simpleName,
            )
            throw failure
        }
    }
}

internal class C6RuntimeInstance internal constructor(
    private val instanceId: String,
    private val accelerator: String,
    private val core: SurveyAICore,
    private val recorder: C6EventRecorder,
) {
    fun launchGenerate(
        scope: CoroutineScope,
        prompt: String,
        cancellationPlan: C6CancellationPlan = C6CancellationPlan(),
    ): C6OwnedRequest {
        val requestId = recorder.allocateRequestId()
        val control = C6RequestControl(recorder, instanceId, requestId, accelerator)
        val deferred =
            scope.async(start = CoroutineStart.LAZY) {
                generate(requestId, prompt, cancellationPlan, control)
            }
        control.attach(deferred)
        deferred.start()
        if (cancellationPlan.trigger == C6CancellationTrigger.IMMEDIATE) {
            control.requestCancel("immediate")
        }
        return C6OwnedRequest(control, deferred)
    }

    suspend fun generate(
        requestId: Long,
        prompt: String,
        cancellationPlan: C6CancellationPlan = C6CancellationPlan(),
        control: C6RequestControl = C6RequestControl(recorder, instanceId, requestId, accelerator),
    ): C6RequestResult {
        val terminal = AtomicBoolean(false)
        val firstDelta = AtomicBoolean(false)
        var deltaCount = 0
        recorder.record(
            C6EventType.GENERATE_BEGIN,
            instanceId = instanceId,
            requestId = requestId,
            accelerator = accelerator,
        )

        try {
            val result =
                core.generate(prompt, onDelta = deltaCallback@{ delta ->
                    if (terminal.get()) {
                        recordCallbackEvent(
                            C6EventType.LATE_CALLBACK,
                            requestId,
                            detail = "deltaLength=${delta.length}",
                        )
                        return@deltaCallback
                    }
                    deltaCount++
                    recordCallbackEvent(
                        C6EventType.DELTA,
                        requestId,
                        detail = "count=$deltaCount length=${delta.length}",
                    )
                    if (firstDelta.compareAndSet(false, true)) {
                        recordCallbackEvent(
                            C6EventType.FIRST_DELTA,
                            requestId,
                        )
                        if (cancellationPlan.trigger == C6CancellationTrigger.FIRST_DELTA) {
                            control.requestCancel("first-delta")
                        }
                    }
                    if (
                        cancellationPlan.trigger == C6CancellationTrigger.AFTER_DELTA_COUNT &&
                            deltaCount >= cancellationPlan.deltaCount.coerceAtLeast(1)
                    ) {
                        control.requestCancel("delta-count=$deltaCount")
                    }
                })
            terminal.set(true)
            return completedResult(requestId, deltaCount, firstDelta.get(), result, control.cancelRequested())
        } catch (cancelled: CancellationException) {
            terminal.set(true)
            recorder.record(
                C6EventType.CANCEL_OBSERVED,
                instanceId = instanceId,
                requestId = requestId,
                accelerator = accelerator,
            )
            recorder.record(
                C6EventType.GENERATE_CANCELLED,
                instanceId = instanceId,
                requestId = requestId,
                accelerator = accelerator,
            )
            throw cancelled
        } catch (failure: Throwable) {
            terminal.set(true)
            recorder.record(
                C6EventType.GENERATE_FAILURE,
                instanceId = instanceId,
                requestId = requestId,
                accelerator = accelerator,
                detail = failure.javaClass.simpleName,
            )
            throw failure
        }
    }

    suspend fun close() {
        recorder.record(C6EventType.CLOSE_BEGIN, instanceId = instanceId, accelerator = accelerator)
        try {
            core.close()
            recorder.record(C6EventType.CLOSE_SUCCESS, instanceId = instanceId, accelerator = accelerator)
        } catch (failure: Throwable) {
            recorder.record(
                C6EventType.CLOSE_FAILURE,
                instanceId = instanceId,
                accelerator = accelerator,
                detail = failure.javaClass.simpleName,
            )
            throw failure
        }
    }

    private fun completedResult(
        requestId: Long,
        deltaCount: Int,
        firstDeltaObserved: Boolean,
        result: SurveyAICoreResult,
        cancelRequested: Boolean,
    ): C6RequestResult {
        recorder.record(
            C6EventType.GENERATE_COMPLETE,
            instanceId = instanceId,
            requestId = requestId,
            accelerator = accelerator,
            detail = "deltas=$deltaCount timedOut=${result.timedOut} error=${result.errorMessage != null}",
        )
        return C6RequestResult(
            requestId = requestId,
            deltaCount = deltaCount,
            firstDeltaObserved = firstDeltaObserved,
            text = result.text,
            durationMs = result.durationMs,
            timedOut = result.timedOut,
            errorMessage = result.errorMessage,
            cancelRequested = cancelRequested,
            cancelObserved = false,
            terminalState = C6RequestTerminalState.COMPLETED,
        )
    }

    private fun recordCallbackEvent(
        type: C6EventType,
        requestId: Long,
        detail: String? = null,
    ) {
        runCatching {
            recorder.record(
                type = type,
                instanceId = instanceId,
                requestId = requestId,
                accelerator = accelerator,
                detail = detail,
            )
        }.onFailure { failure ->
            Log.w(C6EvidenceContract.TAG, "C6 callback bookkeeping failed for $type", failure)
        }
    }
}
