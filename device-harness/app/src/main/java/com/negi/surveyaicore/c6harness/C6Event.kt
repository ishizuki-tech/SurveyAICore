package com.negi.surveyaicore.c6harness

import android.os.SystemClock
import java.util.UUID

internal object C6EvidenceContract {
    const val TAG = "SurveyAICoreC6"
    const val BOUNDARY_TAG = "SurveyAICoreC6Boundary"
    const val MODEL_FILE_NAME = "model.litertlm"
}

internal enum class C6EventType {
    SESSION_BEGIN,
    SCENARIO_BEGIN,
    MODEL_PRECHECK,
    CREATE_BEGIN,
    CREATE_SUCCESS,
    CREATE_FAILURE,
    GENERATE_BEGIN,
    FIRST_DELTA,
    DELTA,
    LATE_CALLBACK,
    GENERATE_COMPLETE,
    GENERATE_CANCELLED,
    GENERATE_FAILURE,
    CANCEL_REQUESTED,
    CANCEL_OBSERVED,
    SAFEPOINT_OR_RECOVERY_OBSERVED,
    CLOSE_BEGIN,
    CLOSE_SUCCESS,
    CLOSE_FAILURE,
    SCENARIO_PASS,
    SCENARIO_FAIL,
    SESSION_END,
}

internal data class C6Event(
    val sessionId: String,
    val scenarioId: String,
    val instanceId: String?,
    val requestId: Long?,
    val accelerator: String?,
    val sequence: Long,
    val elapsedRealtimeMs: Long,
    val type: C6EventType,
    val detail: String? = null,
) {
    fun toEvidenceLine(): String =
        "C6_EVENT session=$sessionId scenario=$scenarioId instance=${instanceId ?: "-"} " +
            "request=${requestId ?: "-"} accelerator=${accelerator ?: "-"} " +
            "sequence=$sequence elapsedMs=$elapsedRealtimeMs type=$type " +
            "detail=${detail ?: "-"}"
}

/**
 * Thread-safe, append-only evidence recorder for C6 harness scenarios.
 * Harness IDs deliberately do not represent LiteRT native run IDs.
 */
internal class C6EventRecorder(
    val sessionId: String = UUID.randomUUID().toString(),
    val scenarioId: String,
) {
    private val lock = Any()
    private val events = mutableListOf<C6Event>()
    private var nextSequence = 1L
    private var nextRequestId = 1L

    fun allocateRequestId(): Long =
        synchronized(lock) {
            nextRequestId++
            nextRequestId - 1L
        }

    fun record(
        type: C6EventType,
        instanceId: String? = null,
        requestId: Long? = null,
        accelerator: String? = null,
        detail: String? = null,
    ): C6Event =
        synchronized(lock) {
            C6Event(
                sessionId = sessionId,
                scenarioId = scenarioId,
                instanceId = instanceId,
                requestId = requestId,
                accelerator = accelerator,
                sequence = nextSequence++,
                elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                type = type,
                detail = detail,
            ).also(events::add)
        }

    fun snapshot(): List<C6Event> = synchronized(lock) { events.toList() }

    fun eventsForRequest(requestId: Long): List<C6Event> =
        synchronized(lock) { events.filter { it.requestId == requestId } }

    fun eventsForInstance(instanceId: String): List<C6Event> =
        synchronized(lock) { events.filter { it.instanceId == instanceId } }
}
