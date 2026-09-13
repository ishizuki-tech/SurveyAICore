package com.negi.surveyaicore.c6harness

import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.surveyaicore.SurveyAICoreAccelerator
import com.negi.surveyaicore.SurveyAICoreConfig
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** C6D performs independent create/close cycles and Activity-lifecycle cancellation recovery. */
@RunWith(AndroidJUnit4::class)
class C6CreateCloseLifecycleInstrumentationTest {
    @Test
    fun createCloseLifecycleRecovery(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = C6EventRecorder(scenarioId = SCENARIO_ID)
        val config =
            SurveyAICoreConfig(
                accelerator = SurveyAICoreAccelerator.GPU,
                maxTokens = 512,
                topK = 1,
                topP = 0.0f,
                temperature = 0.0f,
            )
        val runner = C6RuntimeRunner(context, recorder)
        val cycleInstanceIds = listOf("c6d-cycle-a", "c6d-cycle-b", "c6d-cycle-c")
        val lifecycleInstanceId = "c6d-lifecycle-d"
        val recoveryInstanceId = "c6d-recovery-e"

        recorder.record(C6EventType.SESSION_BEGIN)
        recorder.record(C6EventType.SCENARIO_BEGIN, accelerator = config.accelerator.name)
        try {
            val cycleA = normalCycle(runner, recorder, config, cycleInstanceIds[0])
            val cycleB = normalCycle(runner, recorder, config, cycleInstanceIds[1])
            val cycleC = normalCycle(runner, recorder, config, cycleInstanceIds[2])

            assertTrue(cycleA.closeSequence < cycleB.createSequence)
            assertTrue(cycleB.closeSequence < cycleC.createSequence)

            val lifecycle = lifecycleCancellation(runner, recorder, config, lifecycleInstanceId)
            val recovery = normalCycle(runner, recorder, config, recoveryInstanceId)

            assertTrue(cycleC.closeSequence < lifecycle.beginSequence)
            assertTrue(lifecycle.closeSequence < recovery.createSequence)
            assertEquals(
                5,
                setOf(cycleA.requestId, cycleB.requestId, cycleC.requestId, lifecycle.requestId, recovery.requestId).size,
            )
            assertEquals(
                5,
                setOf(cycleA.instanceId, cycleB.instanceId, cycleC.instanceId, lifecycle.instanceId, recovery.instanceId).size,
            )

            recorder.record(
                C6EventType.SCENARIO_PASS,
                accelerator = config.accelerator.name,
                detail = "requests=5 cycles=3 lifecycleCancel=${lifecycle.requestId} recovery=${recovery.requestId} config=GPU/512/1/0.0/0.0",
            )
        } catch (failure: Throwable) {
            recorder.record(
                C6EventType.SCENARIO_FAIL,
                accelerator = config.accelerator.name,
                detail = failure.javaClass.simpleName,
            )
            throw failure
        } finally {
            recorder.record(C6EventType.SESSION_END, accelerator = config.accelerator.name)
            recorder.snapshot().forEach { Log.i(C6EvidenceContract.TAG, it.toEvidenceLine()) }
        }
    }

    private suspend fun CoroutineScope.normalCycle(
        runner: C6RuntimeRunner,
        recorder: C6EventRecorder,
        config: SurveyAICoreConfig,
        instanceId: String,
    ): NormalCycleOutcome {
        var instance: C6RuntimeInstance? = null
        var closeAttempted = false
        val beforeSequence = recorder.snapshot().lastOrNull()?.sequence ?: 0L
        try {
            instance = runner.create(instanceId, File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, C6EvidenceContract.MODEL_FILE_NAME), config)
            val result = requireNotNull(instance).launchGenerate(this, PROMPT).result.await()
            assertEquals(C6RequestTerminalState.COMPLETED, result.terminalState)
            assertFalse(result.timedOut)
            assertNull(result.errorMessage)
            assertTrue(result.text.isNotBlank())

            closeAttempted = true
            requireNotNull(instance).close()

            val events = recorder.snapshot().filter { it.sequence > beforeSequence && it.instanceId == instanceId }
            val create = events.single { it.type == C6EventType.CREATE_SUCCESS }
            val begin = events.single { it.type == C6EventType.GENERATE_BEGIN }
            val complete = events.single { it.type == C6EventType.GENERATE_COMPLETE }
            val close = events.single { it.type == C6EventType.CLOSE_SUCCESS }
            assertTrue(create.sequence < begin.sequence)
            assertTrue(begin.sequence < complete.sequence)
            assertTrue(complete.sequence < close.sequence)
            assertEquals(begin.requestId, complete.requestId)
            return NormalCycleOutcome(instanceId, begin.requestId!!, create.sequence, close.sequence)
        } finally {
            if (instance != null && !closeAttempted) {
                closeAttempted = true
                instance.close()
            }
        }
    }

    private suspend fun lifecycleCancellation(
        runner: C6RuntimeRunner,
        recorder: C6EventRecorder,
        config: SurveyAICoreConfig,
        instanceId: String,
    ): LifecycleOutcome {
        val started = CompletableDeferred<C6OwnedRequest>()
        var lifecycleJob: Job? = null
        var lifecycleInstance: C6RuntimeInstance? = null
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch<C6LifecycleActivity>(
            Intent(targetContext, C6LifecycleActivity::class.java),
        )
        try {
            scenario.onActivity { activity ->
                lifecycleJob =
                    activity.launchOwnedWorkForTest(
                        close = { lifecycleInstance?.close() },
                        work = {
                            lifecycleInstance =
                                runner.create(
                                    instanceId,
                                    File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, C6EvidenceContract.MODEL_FILE_NAME),
                                    config,
                                )
                            val request = requireNotNull(lifecycleInstance).launchGenerate(CoroutineScope(coroutineContext), PROMPT)
                            request.awaitGenerateBegin()
                            started.complete(request)
                            request.result.await()
                        },
                    ).also { job ->
                        job.invokeOnCompletion { cause ->
                            if (!started.isCompleted) {
                                started.completeExceptionally(
                                    IllegalStateException("Lifecycle request terminated before GENERATE_BEGIN", cause),
                                )
                            }
                        }
                    }
            }

            val request = started.await()
            scenario.moveToState(Lifecycle.State.DESTROYED)
            requireNotNull(lifecycleJob).join()
            assertTrue(requireNotNull(lifecycleJob).isCancelled)

            val requestEvents = recorder.eventsForRequest(request.requestId)
            val begin = requestEvents.single { it.type == C6EventType.GENERATE_BEGIN }
            val cancelled = requestEvents.single { it.type == C6EventType.GENERATE_CANCELLED }
            assertEquals(1, requestEvents.count { it.type == C6EventType.CANCEL_OBSERVED })
            assertTrue(requestEvents.none { it.type == C6EventType.GENERATE_COMPLETE })
            assertTrue(begin.sequence < cancelled.sequence)

            val instanceEvents = recorder.eventsForInstance(instanceId)
            val close = instanceEvents.single { it.type == C6EventType.CLOSE_SUCCESS }
            assertTrue(cancelled.sequence < close.sequence)
            return LifecycleOutcome(instanceId, request.requestId, begin.sequence, cancelled.sequence, close.sequence)
        } finally {
            scenario.close()
        }
    }

    private data class NormalCycleOutcome(
        val instanceId: String,
        val requestId: Long,
        val createSequence: Long,
        val closeSequence: Long,
    )

    private data class LifecycleOutcome(
        val instanceId: String,
        val requestId: Long,
        val beginSequence: Long,
        val cancelledSequence: Long,
        val closeSequence: Long,
    )

    private companion object {
        const val SCENARIO_ID = "c6d-create-close-lifecycle"
        const val PROMPT = "Answer in one short sentence: What is the capital of Japan?"
    }
}
