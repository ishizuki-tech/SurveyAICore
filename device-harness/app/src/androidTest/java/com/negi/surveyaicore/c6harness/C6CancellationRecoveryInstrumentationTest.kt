package com.negi.surveyaicore.c6harness

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.surveyaicore.SurveyAICoreAccelerator
import com.negi.surveyaicore.SurveyAICoreConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** C6C validates event-driven cancellation and same-instance recovery without retries. */
@RunWith(AndroidJUnit4::class)
class C6CancellationRecoveryInstrumentationTest {
    @Test
    fun cancellationRecovery(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = C6EventRecorder(scenarioId = SCENARIO_ID)
        val instanceId = "c6c-cancellation-recovery-instance-1"
        val config =
            SurveyAICoreConfig(
                accelerator = SurveyAICoreAccelerator.GPU,
                maxTokens = 512,
                topK = 1,
                topP = 0.0f,
                temperature = 0.0f,
            )
        val runner = C6RuntimeRunner(context, recorder)
        var instance: C6RuntimeInstance? = null
        var closeAttempted = false

        recorder.record(C6EventType.SESSION_BEGIN)
        recorder.record(C6EventType.SCENARIO_BEGIN, instanceId = instanceId, accelerator = config.accelerator.name)
        try {
            instance = runner.create(instanceId, File(context.filesDir, C6EvidenceContract.MODEL_FILE_NAME), config)
            val created = requireNotNull(instance)

            val request1 = cancellationAttempt(created, recorder, C6CancellationTrigger.IMMEDIATE, firstDeltaRequired = false)
            val request2 = recoveryAttempt(created, recorder)
            val request3 = cancellationAttempt(created, recorder, C6CancellationTrigger.FIRST_DELTA, firstDeltaRequired = true)
            val request4 = recoveryAttempt(created, recorder)

            assertTrue(request1.terminalSequence < request2.startSequence)
            assertTrue(request2.terminalSequence < request3.startSequence)
            assertTrue(request3.terminalSequence < request4.startSequence)
            assertEquals(4, setOf(request1.requestId, request2.requestId, request3.requestId, request4.requestId).size)

            val beforeClose = recorder.snapshot()
            assertEquals(1, beforeClose.count { it.type == C6EventType.CREATE_SUCCESS && it.instanceId == instanceId })
            assertEquals(4, beforeClose.count { it.type == C6EventType.GENERATE_BEGIN && it.instanceId == instanceId })
            assertEquals(2, beforeClose.count { it.type == C6EventType.GENERATE_CANCELLED && it.instanceId == instanceId })
            assertEquals(2, beforeClose.count { it.type == C6EventType.GENERATE_COMPLETE && it.instanceId == instanceId })
            assertTrue(beforeClose.none { it.type == C6EventType.CLOSE_BEGIN || it.type == C6EventType.CLOSE_SUCCESS })

            closeAttempted = true
            created.close()
            val closeSuccess = recorder.snapshot().single { it.type == C6EventType.CLOSE_SUCCESS && it.instanceId == instanceId }
            assertTrue(closeSuccess.sequence > request4.terminalSequence)
            recorder.record(
                C6EventType.SCENARIO_PASS,
                instanceId = instanceId,
                accelerator = config.accelerator.name,
                detail = "requests=4 immediateCancel=${request1.requestId} recovery=${request2.requestId} firstDeltaCancel=${request3.requestId} recovery=${request4.requestId} recoveryNonblank=true config=GPU/512/1/0.0/0.0",
            )
        } catch (failure: Throwable) {
            recorder.record(
                C6EventType.SCENARIO_FAIL,
                instanceId = instanceId,
                accelerator = config.accelerator.name,
                detail = failure.javaClass.simpleName,
            )
            throw failure
        } finally {
            if (instance != null && !closeAttempted) {
                closeAttempted = true
                instance.close()
            }
            recorder.record(C6EventType.SESSION_END, instanceId = instanceId, accelerator = config.accelerator.name)
            recorder.snapshot().forEach { Log.i(C6EvidenceContract.TAG, it.toEvidenceLine()) }
        }
    }

    private suspend fun CoroutineScope.cancellationAttempt(
        instance: C6RuntimeInstance,
        recorder: C6EventRecorder,
        trigger: C6CancellationTrigger,
        firstDeltaRequired: Boolean,
    ): CancellationOutcome {
        val beforeSequence = recorder.snapshot().lastOrNull()?.sequence ?: 0L
        val ownedRequest = instance.launchGenerate(this, PROMPT, C6CancellationPlan(trigger = trigger))
        var cancellationObserved = false
        try {
            ownedRequest.result.await()
        } catch (_: CancellationException) {
            cancellationObserved = true
        }
        assertTrue("C6C cancellation must surface CancellationException", cancellationObserved)

        val events = recorder.snapshot().filter { it.sequence > beforeSequence }
        val requestId = events.single { it.type == C6EventType.GENERATE_BEGIN }.requestId!!
        val cancelRequested = events.filter { it.type == C6EventType.CANCEL_REQUESTED && it.requestId == requestId }
        val cancelObserved = events.filter { it.type == C6EventType.CANCEL_OBSERVED && it.requestId == requestId }
        val cancelled = events.filter { it.type == C6EventType.GENERATE_CANCELLED && it.requestId == requestId }
        assertEquals(1, cancelRequested.size)
        assertEquals(1, cancelObserved.size)
        assertEquals(1, cancelled.size)
        assertTrue(events.none { it.type == C6EventType.GENERATE_COMPLETE && it.requestId == requestId })
        if (firstDeltaRequired) {
            val firstDelta = events.single { it.type == C6EventType.FIRST_DELTA && it.requestId == requestId }
            assertTrue(firstDelta.sequence < cancelRequested.single().sequence)
        }
        return CancellationOutcome(
            requestId = requestId,
            startSequence = events.first { it.type == C6EventType.GENERATE_BEGIN && it.requestId == requestId }.sequence,
            terminalSequence = cancelled.single().sequence,
        )
    }

    private suspend fun CoroutineScope.recoveryAttempt(
        instance: C6RuntimeInstance,
        recorder: C6EventRecorder,
    ): RecoveryOutcome {
        val beforeSequence = recorder.snapshot().lastOrNull()?.sequence ?: 0L
        val ownedRequest = instance.launchGenerate(this, PROMPT)
        val result = ownedRequest.result.await()
        assertEquals(C6RequestTerminalState.COMPLETED, result.terminalState)
        assertFalse(result.timedOut)
        assertNull(result.errorMessage)
        assertTrue(result.text.isNotBlank())

        val events = recorder.snapshot().filter { it.sequence > beforeSequence }
        val requestId = events.single { it.type == C6EventType.GENERATE_BEGIN }.requestId!!
        val completion = events.single { it.type == C6EventType.GENERATE_COMPLETE && it.requestId == requestId }
        assertTrue(events.none { it.type == C6EventType.CANCEL_REQUESTED && it.requestId == requestId })
        return RecoveryOutcome(requestId, events.first { it.type == C6EventType.GENERATE_BEGIN && it.requestId == requestId }.sequence, completion.sequence)
    }

    private data class CancellationOutcome(
        val requestId: Long,
        val startSequence: Long,
        val terminalSequence: Long,
    )

    private data class RecoveryOutcome(
        val requestId: Long,
        val startSequence: Long,
        val terminalSequence: Long,
    )

    private companion object {
        const val SCENARIO_ID = "c6c-cancellation-recovery"
        const val PROMPT = "Answer in one short sentence: What is the capital of Japan?"
    }
}
