package com.negi.surveyaicore.runtime

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Message

private const val TAG = "SLM"

private const val DEBUG_SLM = false

internal typealias ResultListener = (partialResult: String, done: Boolean) -> Unit

internal typealias CleanUpListener = () -> Unit

private inline fun d(message: () -> String) {
    if (DEBUG_SLM) {
        RuntimeLogger.d(TAG, message())
    }
}

internal class StreamDeltaNormalizer(
    modeHint: PartialMode = PartialMode.AUTO,
    private val prefixSampleChars: Int = 128,
    private val boundarySampleChars: Int = 64,
) {
    enum class PartialMode {
        AUTO,
        DELTA,
        ACCUMULATED,
    }

    companion object {
        private const val MIN_STRONG_SAMPLE_CHARS = 16
        private const val SMALL_PREV_FORCE_GROWTH_CHARS = 8
        private const val MIN_GROWTH_CHARS = 1
        private const val ACCUM_MISMATCH_TO_DELTA_THRESHOLD = 2
    }

    private var decided: PartialMode = modeHint
    private var lastLen: Int = 0
    private var prefixSample: String = ""
    private var boundarySample: String = ""
    private var firstChunk: String? = null
    private var firstChunkLen: Int = 0
    private var accumMismatchCount: Int = 0

    fun toDelta(incoming: String): String {
        if (incoming.isEmpty()) return ""

        return when (decided) {
            PartialMode.DELTA -> incoming
            PartialMode.ACCUMULATED -> accumulatedDelta(incoming)
            PartialMode.AUTO -> autoDelta(incoming)
        }
    }

    private fun autoDelta(incoming: String): String {
        if (lastLen == 0) {
            seed(text = incoming, allowFirstChunk = true)
            return incoming
        }

        decided =
            if (looksLikeAccumulated(incoming)) {
                PartialMode.ACCUMULATED
            } else {
                PartialMode.DELTA
            }

        firstChunk = null
        firstChunkLen = 0

        return if (decided == PartialMode.ACCUMULATED) {
            accumulatedDelta(incoming)
        } else {
            seed(text = incoming, allowFirstChunk = false)
            incoming
        }
    }

    private fun accumulatedDelta(incoming: String): String {
        if (lastLen == 0) {
            seed(text = incoming, allowFirstChunk = false)
            accumMismatchCount = 0
            return incoming
        }

        if (!looksLikeAccumulated(incoming)) {
            accumMismatchCount++

            if (accumMismatchCount >= ACCUM_MISMATCH_TO_DELTA_THRESHOLD) {
                decided = PartialMode.DELTA
                d { "StreamDeltaNormalizer: downgrade to DELTA after $accumMismatchCount mismatches" }
            }

            seed(text = incoming, allowFirstChunk = false)
            return incoming
        }

        accumMismatchCount = 0

        val delta =
            if (incoming.length >= lastLen) {
                incoming.substring(lastLen)
            } else {
                incoming
            }

        seed(text = incoming, allowFirstChunk = false)
        return delta
    }

    private fun seed(text: String, allowFirstChunk: Boolean) {
        lastLen = text.length
        prefixSample = text.take(prefixSampleChars)
        boundarySample = text.takeLast(boundarySampleChars)

        if (allowFirstChunk) {
            val cap = 4_096
            val canKeep = text.length in MIN_STRONG_SAMPLE_CHARS..cap
            firstChunk = if (canKeep) text else null
            firstChunkLen = text.length
        }
    }

    private fun looksLikeAccumulated(incoming: String): Boolean {
        if (incoming.length < lastLen) return false

        val growth = incoming.length - lastLen
        if (growth < MIN_GROWTH_CHARS) return false

        if (prefixSample.isNotEmpty() && !incoming.startsWith(prefixSample)) {
            return false
        }

        val first = firstChunk
        if (
            first != null &&
            firstChunkLen >= MIN_STRONG_SAMPLE_CHARS &&
            incoming.length >= firstChunkLen &&
            incoming.startsWith(first)
        ) {
            return true
        }

        if (lastLen < MIN_STRONG_SAMPLE_CHARS && growth < SMALL_PREV_FORCE_GROWTH_CHARS) {
            return false
        }

        if (prefixSample.length >= MIN_STRONG_SAMPLE_CHARS && !incoming.startsWith(prefixSample)) {
            return false
        }

        if (boundarySample.length >= MIN_STRONG_SAMPLE_CHARS) {
            val start = (lastLen - boundarySample.length).coerceAtLeast(0)
            val boundaryMatches =
                incoming.regionMatches(
                    thisOffset = start,
                    other = boundarySample,
                    otherOffset = 0,
                    length = boundarySample.length,
                    ignoreCase = false,
                )

            if (!boundaryMatches) return false
        }

        return true
    }
}

internal object SLM {
    fun setApplicationContext(context: Context) {
        val appContext = context.applicationContext ?: context
        LiteRtLM.setApplicationContext(appContext)
    }

    suspend fun initializeIfNeeded(
        context: Context,
        model: RuntimeModel,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val appContext = context.applicationContext ?: context

        d {
            "initializeIfNeeded: model='${model.name}' image=$supportImage audio=$supportAudio"
        }

        LiteRtLM.initializeIfNeeded(
            context = appContext,
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemMessage = systemMessage,
            tools = tools,
        )
    }

    suspend fun resetConversationAndWait(
        model: RuntimeModel,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        d { "resetConversationAndWait: model='${model.name}' image=$supportImage audio=$supportAudio" }

        LiteRtLM.resetConversationAndWait(
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemMessage = systemMessage,
            tools = tools,
        )
    }

    suspend fun forceCleanUpAndWait(model: RuntimeModel) {
        d { "forceCleanUpAndWait: model='${model.name}'" }
        LiteRtLM.forceCleanUpAndWait(model = model)
    }

    fun runInference(
        model: RuntimeModel,
        input: String,
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener,
        onError: (message: String) -> Unit = {},
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onRunStarted: (Long) -> Unit = {},
    ) {
        d {
            "runInference: model='${model.name}' textLen=${input.length} " +
                    "images=${images.size} audio=${audioClips.size}"
        }

        LiteRtLM.runInference(
            model = model,
            input = input,
            resultListener = resultListener,
            cleanUpListener = cleanUpListener,
            onError = onError,
            images = images,
            audioClips = audioClips,
            notifyCancelToOnError = false,
            onRunStarted = onRunStarted,
        )
    }

    fun cancel(model: RuntimeModel) {
        d { "cancel: model='${model.name}'" }
        LiteRtLM.cancel(model)
    }

    fun cancel(model: RuntimeModel, expectedRunId: Long) {
        d { "cancel: model='${model.name}' expectedRunId=$expectedRunId" }
        LiteRtLM.cancel(model = model, expectedRunId = expectedRunId)
    }
}
