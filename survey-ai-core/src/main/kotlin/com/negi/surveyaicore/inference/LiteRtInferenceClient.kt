@file:Suppress("MemberVisibilityCanBePrivate")

package com.negi.surveyaicore.inference

import android.content.Context
import android.os.SystemClock
import com.negi.surveyaicore.runtime.RuntimeLogger
import com.negi.surveyaicore.runtime.SLM
import com.negi.surveyaicore.runtime.RuntimeModel
import com.negi.surveyaicore.runtime.StreamDeltaNormalizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val AI_INFERENCE_GATE = Semaphore(1)

internal class LiteRtInferenceClient(
    context: Context,
) : InferenceClient {

    private val appContext = context.applicationContext ?: context

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        SLM.setApplicationContext(appContext)
    }

    override suspend fun initialize(model: RuntimeModel): Result<Unit> =
        runCatchingSuspend {
            val initAttempt =
                AI_INFERENCE_GATE.withPermit {
                    withTimeoutOrNull(INIT_TIMEOUT_MS) {
                        runOnSlmThread {
                            SLM.initializeIfNeeded(
                                context = appContext,
                                model = model,
                                supportImage = false,
                                supportAudio = false,
                            )
                        }
                    }
                }

            if (initAttempt == null) {
                FORCE_REINIT.set(true)
                error("SLM.initializeIfNeeded timed out after ${INIT_TIMEOUT_MS}ms")
            }
        }

    override suspend fun generate(
        model: RuntimeModel,
        prompt: String,
        onDelta: (String) -> Unit,
    ): InferenceOutput {
        val startedAt = SystemClock.elapsedRealtime()
        val raw = StringBuilder()

        return try {
            request(model, prompt).collect { delta ->
                raw.append(delta)
                onDelta(delta)
            }

            InferenceOutput(
                rawText = raw.toString(),
                durationMs = SystemClock.elapsedRealtime() - startedAt,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            InferenceOutput(
                rawText = raw.toString(),
                durationMs = SystemClock.elapsedRealtime() - startedAt,
                timedOut = isTimeoutThrowable(t),
                error = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    override suspend fun reset(model: RuntimeModel): Result<Unit> =
        runCatchingSuspend {
            AI_INFERENCE_GATE.withPermit {
                runOnSlmThread {
                    SLM.resetConversationAndWait(
                        model = model,
                        supportImage = false,
                        supportAudio = false,
                    )
                }
            }
        }

    override suspend fun close(model: RuntimeModel?) {
        if (model == null) {
            repoScope.cancel("LiteRtInferenceClient closed without model")
            return
        }

        runCatchingSuspend {
            runCancelOnSlmThread {
                SLM.cancel(model)
            }
        }.onFailure { error ->
            RuntimeLogger.w(TAG, "close cancel failed: ${error.message}", error)
        }

        try {
            runOnSlmThread {
                SLM.forceCleanUpAndWait(model)
            }
        } finally {
            repoScope.cancel("LiteRtInferenceClient closed")
        }
    }

    private fun request(model: RuntimeModel, prompt: String): Flow<String> {
        return callbackFlow {
            val out = this

            val requestId = REQ_SEQ.incrementAndGet()
            val gateRequestedAt = SystemClock.elapsedRealtime()

            val internalClose = AtomicBoolean(false)
            val responseStreamClosed = AtomicBoolean(false)
            val collectorClosed = AtomicBoolean(false)
            val gateActive = AtomicBoolean(false)
            val inferenceStarted = AtomicBoolean(false)
            val cancelIssued = AtomicBoolean(false)
            val scopedCancelDispatched = AtomicBoolean(false)
            val liteRtRunId = AtomicLong(0L)
            val cancelTag = AtomicReference<String?>(null)
            val cancelGraceHook = AtomicReference<((String, Throwable?) -> Unit)?>(null)

            fun markForceReinit(reason: String) {
                if (SLM.nativeRuntimePoisonErrorOrNull() != null) {
                    RuntimeLogger.w(TAG, "[$requestId] runtime poisoned; skipping FORCE_REINIT reason='$reason'")
                    return
                }
                FORCE_REINIT.set(true)
                RuntimeLogger.w(TAG, "[$requestId] FORCE_REINIT=true reason='$reason'")
            }

            fun dispatchScopedCancelIfReady() {
                if (!cancelIssued.get()) return

                val expectedRunId = liteRtRunId.get()
                if (expectedRunId <= 0L) return
                if (!scopedCancelDispatched.compareAndSet(false, true)) return

                repoScope.launch {
                    runCatchingSuspend {
                        runCancelOnSlmThread {
                            SLM.cancel(
                                model = model,
                                expectedRunId = expectedRunId,
                            )
                        }
                    }.onFailure { e ->
                        val tag = cancelTag.get()
                        RuntimeLogger.w(TAG, "[$requestId] cancel failed tag='$tag': ${e.message}", e)
                    }
                }
            }

            fun issueBackendCancel(tag: String) {
                cancelTag.compareAndSet(null, tag)
                if (!cancelIssued.compareAndSet(false, true)) return

                dispatchScopedCancelIfReady()
                RuntimeLogger.w(TAG, "[$requestId] cancel requested tag='$tag'")
            }

            val driverJob = repoScope.launch(Dispatchers.Default) {
                try {
                    AI_INFERENCE_GATE.withPermit {
                        gateActive.set(true)

                        val completion = CompletableDeferred<String>()
                        val closed = AtomicBoolean(false)
                        val finalized = AtomicBoolean(false)
                        val nativeFinishScheduled = AtomicBoolean(false)
                        val cancelGraceScheduled = AtomicBoolean(false)
                        val terminalFailure = AtomicReference<Throwable?>(null)
                        val terminalFailureReason = AtomicReference<String?>(null)

                        val requestStartedAt = SystemClock.elapsedRealtime()
                        val inferenceStartedAt = AtomicLong(-1L)
                        val firstTokenAt = AtomicLong(-1L)
                        val lastEventAt = AtomicLong(-1L)
                        val lastDeltaAt = AtomicLong(-1L)
                        val logicalDoneAt = AtomicLong(-1L)

                        val logicalDone = AtomicBoolean(false)
                        val nativeTerminated = AtomicBoolean(false)
                        val chunks = AtomicLong(0L)
                        val emittedChars = AtomicLong(0L)
                        val repeatedDelta = AtomicReference<String?>(null)
                        val repeatedDeltaCount = AtomicLong(0L)

                        val normalizer =
                            StreamDeltaNormalizer(
                                StreamDeltaNormalizer.PartialMode.DELTA
                            )

                        fun recordTerminalFailure(reason: String, cause: Throwable) {
                            terminalFailure.compareAndSet(null, cause)
                            terminalFailureReason.compareAndSet(null, reason)
                        }

                        fun finalizeOnce(reason: String, cause: Throwable? = null) {
                            if (!finalized.compareAndSet(false, true)) return

                            val now = SystemClock.elapsedRealtime()
                            val inferenceStart = inferenceStartedAt.get()
                            val totalMs = now - requestStartedAt
                            val inferenceMs =
                                if (inferenceStart > 0L) now - inferenceStart else -1L
                            val firstMs =
                                firstTokenAt.get().let {
                                    if (it >= 0L && inferenceStart > 0L) it - inferenceStart else -1L
                                }

                            RuntimeLogger.d(
                                TAG,
                                "[$requestId] finalize reason='$reason' totalMs=$totalMs " +
                                        "inferenceMs=$inferenceMs firstTokenMs=$firstMs " +
                                        "chunks=${chunks.get()} emittedChars=${emittedChars.get()} " +
                                        "logicalDone=${logicalDone.get()} nativeTerminated=${nativeTerminated.get()}",
                            )

                            if (cause != null && DEBUG_ERRORS) {
                                RuntimeLogger.w(TAG, "[$requestId] finalize exception", cause)
                            }
                        }

                        fun closeResponseStreamOnce() {
                            if (!responseStreamClosed.compareAndSet(false, true)) return

                            internalClose.set(true)
                            runCatching { out.close() }
                        }

                        fun closeOnce(reason: String, cause: Throwable? = null) {
                            if (!closed.compareAndSet(false, true)) return

                            internalClose.set(true)
                            finalizeOnce(reason, cause)

                            if (!completion.isCompleted) {
                                completion.complete(reason)
                            }

                            if (responseStreamClosed.compareAndSet(false, true)) {
                                runCatching {
                                    if (cause != null) out.close(cause) else out.close()
                                }
                            }
                        }

                        fun scheduleCancelGrace(
                            reason: String,
                            cause: Throwable? = null,
                        ) {
                            if (cause != null) {
                                recordTerminalFailure(reason, cause)
                            }

                            if (!cancelGraceScheduled.compareAndSet(false, true)) return

                            repoScope.launch cancelGrace@{
                                delay(CANCEL_GRACE_TIMEOUT_MS)

                                if (closed.get() || nativeTerminated.get()) {
                                    return@cancelGrace
                                }

                                markForceReinit("$reason-cancel-grace-expired")

                                val forceResult =
                                    runCatchingSuspend {
                                        runOnSlmThread {
                                            SLM.forceCleanUpAndWait(model = model)
                                        }
                                    }

                                if (nativeFinishScheduled.get()) {
                                    return@cancelGrace
                                }

                                val forceFailure = forceResult.exceptionOrNull()

                                if (forceFailure == null) {
                                    nativeTerminated.set(true)
                                    FORCE_REINIT.set(false)
                                    RuntimeLogger.w(
                                        TAG,
                                        "[$requestId] forced recovery teardown completed after grace timeout",
                                    )
                                } else {
                                    RuntimeLogger.e(
                                        TAG,
                                        "[$requestId] forced recovery teardown failed: ${forceFailure.message}",
                                        forceFailure,
                                    )

                                    if (terminalFailure.get() == null) {
                                        recordTerminalFailure(
                                            "$reason-force-cleanup-failed",
                                            forceFailure,
                                        )
                                    }
                                }

                                closeOnce(
                                    terminalFailureReason.get()
                                        ?: "$reason-cancel-grace-expired",
                                    terminalFailure.get(),
                                )
                            }
                        }

                        cancelGraceHook.set(::scheduleCancelGrace)

                        fun finishAfterNativeSafepoint() {
                            if (!nativeFinishScheduled.compareAndSet(false, true)) return

                            nativeTerminated.set(true)

                            repoScope.launch {
                                val fullRecoveryRequired = FORCE_REINIT.get()
                                var repairFailed = false

                                try {
                                    if (fullRecoveryRequired) {
                                        runOnSlmThread {
                                            SLM.forceCleanUpAndWait(model = model)
                                        }

                                        FORCE_REINIT.set(false)
                                        RuntimeLogger.d(
                                            TAG,
                                            "[$requestId] full runtime teardown completed after safepoint",
                                        )
                                    } else {
                                        runOnSlmThread {
                                            SLM.resetConversationAndWait(
                                                model = model,
                                                supportImage = false,
                                                supportAudio = false,
                                            )
                                        }

                                        RuntimeLogger.d(
                                            TAG,
                                            "[$requestId] Conversation reset completed after safepoint",
                                        )
                                    }
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    repairFailed = true
                                    markForceReinit("post-safepoint-repair-failed")
                                    RuntimeLogger.w(
                                        TAG,
                                        "[$requestId] post-safepoint repair failed: ${t.message}",
                                        t,
                                    )
                                } finally {
                                    closeOnce(
                                        terminalFailureReason.get()
                                            ?: if (repairFailed) {
                                                "native-terminated-repair-failed"
                                            } else {
                                                "native-terminated"
                                            },
                                        terminalFailure.get(),
                                    )
                                }
                            }
                        }

                        if (FORCE_REINIT.getAndSet(false)) {
                            val repairResult =
                                runCatchingSuspend {
                                    runOnSlmThread {
                                        SLM.forceCleanUpAndWait(model = model)
                                    }
                                }

                            val repairFailure = repairResult.exceptionOrNull()

                            if (repairFailure != null) {
                                markForceReinit("pre-run-repair-failed")
                                closeOnce("pre-run-repair-failed", repairFailure)
                            } else {
                                RuntimeLogger.d(TAG, "[$requestId] pre-run full teardown completed")
                            }
                        }

                        if (collectorClosed.get() && !closed.get()) {
                            closeOnce("collector-cancelled-before-init")
                        }

                        val cappedPrompt =
                            capPromptIfNeeded(
                                prompt.normalizePrompt(),
                                PROMPT_CHAR_CAP,
                                PROMPT_KEEP_HEAD_CHARS,
                                PROMPT_KEEP_TAIL_CHARS,
                            )

                        val gateWaitMs = SystemClock.elapsedRealtime() - gateRequestedAt
                        RuntimeLogger.d(
                            TAG,
                            "[$requestId] request start model='${model.name}' " +
                                    "prompt.len=${cappedPrompt.length} gateWaitMs=$gateWaitMs",
                        )

                        if (!closed.get()) {
                            val initT0 = SystemClock.elapsedRealtime()

                            val initAttempt =
                                withTimeoutOrNull(INIT_TIMEOUT_MS) {
                                    runCatchingSuspend {
                                        runOnSlmThread {
                                            SLM.initializeIfNeeded(
                                                context = appContext,
                                                model = model,
                                                supportImage = false,
                                                supportAudio = false,
                                            )
                                        }
                                    }
                                }

                            val initMs = SystemClock.elapsedRealtime() - initT0

                            when {
                                initAttempt == null -> {
                                    val e =
                                        RuntimeException(
                                            "SLM.initializeIfNeeded timed out after ${INIT_TIMEOUT_MS}ms"
                                        )
                                    markForceReinit("init-timeout")
                                    closeOnce("init-timeout", e)
                                }

                                initAttempt.isFailure -> {
                                    val e =
                                        initAttempt.exceptionOrNull()
                                            ?: RuntimeException("init-error")
                                    markForceReinit("init-error")
                                    closeOnce("init-error", e)
                                }

                                else -> {
                                    RuntimeLogger.d(
                                        TAG,
                                        "[$requestId] initializeIfNeeded ok initMs=$initMs",
                                    )
                                }
                            }
                        }

                        if (collectorClosed.get() && !closed.get()) {
                            markForceReinit("collector-cancelled-during-setup")
                            closeOnce("collector-cancelled-before-inference")
                        }

                        if (!closed.get()) {
                            val start = SystemClock.elapsedRealtime()
                            inferenceStartedAt.set(start)
                            firstTokenAt.set(-1L)
                            lastEventAt.set(start)
                            lastDeltaAt.set(start)
                            inferenceStarted.set(true)

                            repoScope.launch {
                                while (isActive && !closed.get()) {
                                    val now = SystemClock.elapsedRealtime()
                                    val elapsed = now - inferenceStartedAt.get()
                                    val hasToken = firstTokenAt.get() >= 0L

                                    when {
                                        elapsed >= HARD_WATCHDOG_MS -> {
                                            val r = "hard-watchdog-timeout"
                                            val e =
                                                RuntimeException(
                                                    "Inference exceeded ${HARD_WATCHDOG_MS}ms"
                                                )
                                            issueBackendCancel(r)
                                            markForceReinit(r)
                                            scheduleCancelGrace(r, e)
                                            break
                                        }

                                        !hasToken && elapsed >= FIRST_TOKEN_TIMEOUT_MS -> {
                                            val r = "first-token-timeout"
                                            val e =
                                                RuntimeException(
                                                    "No first token within ${FIRST_TOKEN_TIMEOUT_MS}ms"
                                                )
                                            issueBackendCancel(r)
                                            markForceReinit(r)
                                            scheduleCancelGrace(r, e)
                                            break
                                        }

                                        hasToken &&
                                                !logicalDone.get() &&
                                                now - lastEventAt.get() >= EVENT_STALL_TIMEOUT_MS -> {
                                            val r = "event-stall-timeout"
                                            val e =
                                                RuntimeException(
                                                    "Inference stream stalled for ${EVENT_STALL_TIMEOUT_MS}ms"
                                                )
                                            issueBackendCancel(r)
                                            markForceReinit(r)
                                            scheduleCancelGrace(r, e)
                                            break
                                        }

                                        logicalDone.get() &&
                                                logicalDoneAt.get() > 0L &&
                                                now - logicalDoneAt.get() >= POST_DONE_TIMEOUT_MS -> {
                                            val r = "post-done-termination-timeout"
                                            issueBackendCancel(r)
                                            markForceReinit(r)
                                            scheduleCancelGrace(r)
                                            break
                                        }
                                    }

                                    delay(PROGRESS_POLL_MS)
                                }
                            }

                            var messageCount = 0L

                            try {
                                runOnSlmThread {
                                    SLM.runInference(
                                        model = model,
                                        input = cappedPrompt,
                                        resultListener = { partial, done ->
                                            if (
                                                closed.get() ||
                                                collectorClosed.get() ||
                                                logicalDone.get()
                                            ) {
                                                return@runInference
                                            }

                                            lastEventAt.set(SystemClock.elapsedRealtime())
                                            messageCount++

                                            val delta = normalizer.toDelta(partial)

                                            if (delta.isNotEmpty()) {
                                                if (firstTokenAt.get() < 0L) {
                                                    firstTokenAt.compareAndSet(
                                                        -1L,
                                                        SystemClock.elapsedRealtime(),
                                                    )
                                                }

                                                lastDeltaAt.set(SystemClock.elapsedRealtime())
                                                chunks.incrementAndGet()

                                                if (repeatedLoopDetected(delta, repeatedDelta, repeatedDeltaCount)) {
                                                    val r = "repeated-delta-loop"
                                                    val e =
                                                        IllegalStateException(
                                                            "Repeated model delta detected " +
                                                                    "${repeatedDeltaCount.get()} times"
                                                        )
                                                    issueBackendCancel(r)
                                                    markForceReinit(r)
                                                    scheduleCancelGrace(r, e)
                                                    return@runInference
                                                }

                                                val remaining =
                                                    OUTPUT_CHAR_HARD_CAP - emittedChars.get()

                                                if (remaining <= 0L) {
                                                    val r = "output-char-limit"
                                                    val e =
                                                        IllegalStateException(
                                                            "Model output exceeded $OUTPUT_CHAR_HARD_CAP characters"
                                                        )
                                                    issueBackendCancel(r)
                                                    markForceReinit(r)
                                                    scheduleCancelGrace(r, e)
                                                    return@runInference
                                                }

                                                val accepted =
                                                    if (delta.length.toLong() <= remaining) {
                                                        delta
                                                    } else {
                                                        delta.take(remaining.toInt())
                                                    }

                                                emittedChars.addAndGet(accepted.length.toLong())

                                                if (!out.trySend(accepted).isSuccess) {
                                                    collectorClosed.set(true)
                                                    issueBackendCancel("channel-closed")
                                                    markForceReinit("channel-closed")
                                                    scheduleCancelGrace("channel-closed")
                                                    return@runInference
                                                }

                                                if (accepted.length != delta.length) {
                                                    val r = "output-char-limit"
                                                    val e =
                                                        IllegalStateException(
                                                            "Model output exceeded $OUTPUT_CHAR_HARD_CAP characters"
                                                        )
                                                    issueBackendCancel(r)
                                                    markForceReinit(r)
                                                    scheduleCancelGrace(r, e)
                                                    return@runInference
                                                }
                                            }

                                            if (DEBUG_STREAM && (messageCount == 1L || messageCount % DEBUG_STREAM_EVERY_N == 0L)) {
                                                RuntimeLogger.d(
                                                    TAG,
                                                    "stream[rid=$requestId msg#$messageCount] done=$done deltaLen=${delta.length}",
                                                )
                                            }

                                            if (done) {
                                                if (logicalDone.compareAndSet(false, true)) {
                                                    logicalDoneAt.set(SystemClock.elapsedRealtime())
                                                }

                                                if (terminalFailure.get() == null && cancelTag.get() == null) {
                                                    closeResponseStreamOnce()
                                                }

                                                RuntimeLogger.d(
                                                    TAG,
                                                    "[$requestId] logical done=true; " +
                                                            "responseClosed=${responseStreamClosed.get()}; waiting native cleanup",
                                                )
                                            }
                                        },
                                        cleanUpListener = {
                                            lastEventAt.set(SystemClock.elapsedRealtime())
                                            RuntimeLogger.d(TAG, "[$requestId] native cleanup safepoint")
                                            finishAfterNativeSafepoint()
                                        },
                                        onError = { message ->
                                            if (closed.get()) {
                                                return@runInference
                                            }

                                            lastEventAt.set(SystemClock.elapsedRealtime())

                                            val msg = message.trim()
                                            val upper = msg.uppercase(Locale.US)
                                            val cancelled =
                                                upper.contains("CANCELLED") ||
                                                        upper.contains("CANCELED")
                                            val tag = cancelTag.get()

                                            if (cancelled && tag != null) {
                                                RuntimeLogger.w(
                                                    TAG,
                                                    "[$requestId] onError(cancelled) tag='$tag' msg='$msg'",
                                                )
                                                scheduleCancelGrace(reason = tag)
                                                return@runInference
                                            }

                                            val error =
                                                RuntimeException(
                                                    msg.ifBlank {
                                                        "LiteRT-LM inference error"
                                                    }
                                                )

                                            recordTerminalFailure(
                                                reason = "error",
                                                cause = error,
                                            )
                                            markForceReinit("onError")

                                            if (logicalDone.compareAndSet(false, true)) {
                                                logicalDoneAt.set(SystemClock.elapsedRealtime())
                                            }

                                            scheduleCancelGrace(
                                                reason = "error",
                                                cause = error,
                                            )
                                        },
                                        onRunStarted = { runId ->
                                            val published =
                                                liteRtRunId.compareAndSet(0L, runId)

                                            if (!published && liteRtRunId.get() != runId) {
                                                RuntimeLogger.e(
                                                    TAG,
                                                    "[$requestId] conflicting LiteRtLM run publication: " +
                                                            "current=${liteRtRunId.get()} received=$runId",
                                                )
                                                return@runInference
                                            }

                                            dispatchScopedCancelIfReady()
                                        },
                                    )
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                RuntimeLogger.e(
                                    TAG,
                                    "[$requestId] runInference threw: ${t.message}",
                                    t,
                                )
                                markForceReinit("exception")
                                closeOnce("exception", t)
                            }
                        }

                        completion.await()
                    }
                } catch (ce: CancellationException) {
                    if (!collectorClosed.get()) {
                        runCatching { out.close(ce) }
                    }
                    throw ce
                } catch (t: Throwable) {
                    RuntimeLogger.e(
                        TAG,
                        "[$requestId] inference driver failed: ${t.message}",
                        t,
                    )
                    runCatching { out.close(t) }
                } finally {
                    gateActive.set(false)
                    inferenceStarted.set(false)
                    cancelGraceHook.set(null)
                }
            }

            awaitClose {
                if (internalClose.get()) {
                    return@awaitClose
                }

                collectorClosed.set(true)

                when {
                    !gateActive.get() -> {
                        driverJob.cancel(
                            CancellationException(
                                "collector closed before AI gate acquisition"
                            )
                        )

                        RuntimeLogger.d(
                            TAG,
                            "[$requestId] collector cancelled while waiting for AI gate",
                        )
                    }

                    inferenceStarted.get() -> {
                        issueBackendCancel("collector-cancelled")
                        markForceReinit("collector-cancelled")
                        cancelGraceHook.get()?.invoke(
                            "collector-cancelled",
                            null,
                        )

                        RuntimeLogger.w(
                            TAG,
                            "[$requestId] collector cancelled; backend cancel requested " +
                                    "and gate retained until safepoint/grace timeout",
                        )
                    }

                    else -> {
                        markForceReinit("collector-cancelled-during-setup")

                        RuntimeLogger.w(
                            TAG,
                            "[$requestId] collector cancelled during setup; waiting for safe setup exit",
                        )
                    }
                }
            }
        }
            .buffer(Channel.BUFFERED)
            .flowOn(Dispatchers.Default)
    }

    private fun capPromptIfNeeded(
        prompt: String,
        maxChars: Int,
        keepHeadChars: Int,
        keepTailChars: Int,
    ): String {
        if (prompt.length <= maxChars) return prompt

        val headKeep = keepHeadChars.coerceIn(4_096, maxChars)
        val tailKeep = keepTailChars.coerceIn(4_096, maxChars)

        val head = prompt.take(headKeep)
        val tail = prompt.takeLast(tailKeep)
        val dropped = prompt.length - (head.length + tail.length)
        val marker =
            "\n[TRUNCATED: dropped=${dropped.coerceAtLeast(0)} chars; " +
                    "kept_head=${head.length}; kept_tail=${tail.length}]\n"

        var out = head + marker + tail
        if (out.length <= maxChars) return out

        val overflow = out.length - maxChars
        val newTailLen = (tail.length - overflow).coerceAtLeast(4_096)
        out = head + marker + tail.takeLast(newTailLen)

        return if (out.length <= maxChars) out else out.takeLast(maxChars)
    }

    private fun String.normalizePrompt(): String =
        replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()

    private fun repeatedLoopDetected(
        delta: String,
        repeatedDelta: AtomicReference<String?>,
        repeatedDeltaCount: AtomicLong,
    ): Boolean {
        if (delta.isBlank() || delta.length > REPEATED_DELTA_MAX_CHARS) {
            repeatedDelta.set(null)
            repeatedDeltaCount.set(0L)
            return false
        }

        val count =
            if (repeatedDelta.get() == delta) {
                repeatedDeltaCount.incrementAndGet()
            } else {
                repeatedDelta.set(delta)
                repeatedDeltaCount.set(1L)
                1L
            }

        return count >= REPEATED_DELTA_LIMIT
    }

    private fun isTimeoutThrowable(t: Throwable): Boolean {
        val message = t.message.orEmpty().lowercase(Locale.US)
        return message.contains("timeout") || message.contains("timed out")
    }

    private suspend fun <T> runOnSlmThread(block: suspend () -> T): T {
        return withContext(SLM_DISPATCHER) { block() }
    }

    private suspend fun runCancelOnSlmThread(block: suspend () -> Unit) {
        withContext(SLM_CANCEL_DISPATCHER) { block() }
    }

    private suspend inline fun <T> runCatchingSuspend(
        crossinline block: suspend () -> T,
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    companion object {
        private const val TAG = "LiteRtInferenceClient"

        private val REQ_SEQ = AtomicLong(0L)

        private const val INIT_TIMEOUT_MS = 90_000L
        private const val HARD_WATCHDOG_MS = 120_000L
        private const val FIRST_TOKEN_TIMEOUT_MS = 45_000L
        private const val EVENT_STALL_TIMEOUT_MS = 12_000L
        private const val POST_DONE_TIMEOUT_MS = 30_000L
        private const val CANCEL_GRACE_TIMEOUT_MS = 5_000L
        private const val PROGRESS_POLL_MS = 250L

        private const val OUTPUT_CHAR_HARD_CAP = 120_000L
        private const val REPEATED_DELTA_LIMIT = 48L
        private const val REPEATED_DELTA_MAX_CHARS = 32

        private const val DEBUG_STREAM = false
        private const val DEBUG_ERRORS = false
        private const val DEBUG_STREAM_EVERY_N = 8

        private const val PROMPT_CHAR_CAP: Int = 120_000
        private const val PROMPT_KEEP_HEAD_CHARS: Int = 48_000
        private const val PROMPT_KEEP_TAIL_CHARS: Int = 24_000

        private val FORCE_REINIT = AtomicBoolean(false)

        private val SLM_DISPATCHER by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "slm-jni").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }

        private val SLM_CANCEL_DISPATCHER by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "slm-cancel").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }
    }
}
