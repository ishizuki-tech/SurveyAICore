package com.negi.surveyaicore.c6harness

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.surveyaicore.SurveyAICoreAccelerator
import com.negi.surveyaicore.SurveyAICoreConfig
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** C6E proves concurrent public demand across four independent public instances. */
@RunWith(AndroidJUnit4::class)
class C6MultipleInstanceSerializationInstrumentationTest {
    @Test
    fun multipleInstancesSerializeConcurrentDemand(): Unit = runBlocking {
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
        val instanceIds = List(4) { index -> "c6e-instance-${index + 1}" }
        val instances = mutableListOf<ManagedInstance>()
        val closedInstanceIds = mutableSetOf<String>()
        var primaryFailure: Throwable? = null

        recorder.record(C6EventType.SESSION_BEGIN)
        recorder.record(C6EventType.SCENARIO_BEGIN, accelerator = config.accelerator.name)
        try {
            instanceIds.forEach { instanceId ->
                instances +=
                    ManagedInstance(
                        instanceId,
                        runner.create(
                            instanceId,
                            File(context.filesDir, C6EvidenceContract.MODEL_FILE_NAME),
                            config,
                        ),
                    )
            }
            assertEquals(4, instances.size)

            val readySignals = List(4) { CompletableDeferred<Unit>() }
            val release = CompletableDeferred<Unit>()
            val requestJobs =
                instances.mapIndexed { index, managed ->
                    async {
                        recorder.record(
                            C6EventType.DEMAND_READY,
                            instanceId = managed.instanceId,
                            accelerator = config.accelerator.name,
                        )
                        readySignals[index].complete(Unit)
                        release.await()
                        managed.instance.launchGenerate(this, PROMPT).result.await()
                    }
                }

            readySignals.forEach { it.await() }
            recorder.record(C6EventType.DEMAND_RELEASED, accelerator = config.accelerator.name)
            assertTrue(release.complete(Unit))

            val results = requestJobs.awaitAll()
            assertEquals(4, results.size)
            assertEquals(4, results.map(C6RequestResult::requestId).toSet().size)
            results.forEachIndexed { index, result ->
                assertEquals("request ${index + 1} must complete", C6RequestTerminalState.COMPLETED, result.terminalState)
                assertFalse("request ${index + 1} must not time out", result.timedOut)
                assertNull("request ${index + 1} must not report an error", result.errorMessage)
                assertTrue("request ${index + 1} output must be nonblank", result.text.isNotBlank())
                assertFalse("request ${index + 1} must not request cancellation", result.cancelRequested)
            }

            val beforeClose = recorder.snapshot()
            val readyEvents = beforeClose.filter { it.type == C6EventType.DEMAND_READY }
            val releaseEvent = beforeClose.single { it.type == C6EventType.DEMAND_RELEASED }
            assertEquals(4, readyEvents.size)
            assertEquals(instanceIds.toSet(), readyEvents.mapNotNull(C6Event::instanceId).toSet())
            assertTrue(readyEvents.all { it.sequence < releaseEvent.sequence })
            assertEquals(4, beforeClose.count { it.type == C6EventType.CREATE_SUCCESS })
            assertEquals(4, beforeClose.count { it.type == C6EventType.GENERATE_BEGIN })
            assertEquals(4, beforeClose.count { it.type == C6EventType.GENERATE_COMPLETE })
            assertTrue(beforeClose.none { it.type == C6EventType.GENERATE_CANCELLED })
            assertTrue(beforeClose.none { it.type == C6EventType.GENERATE_FAILURE })
            assertTrue(beforeClose.none { it.type == C6EventType.CANCEL_REQUESTED })
            assertTrue(beforeClose.none { it.type == C6EventType.CANCEL_OBSERVED })
            assertTrue(beforeClose.none { it.type == C6EventType.LATE_CALLBACK })
            assertTrue(beforeClose.none { it.type == C6EventType.CLOSE_BEGIN || it.type == C6EventType.CLOSE_SUCCESS })

            val requestIdsByInstance =
                results.zip(instanceIds).associate { (result, instanceId) -> result.requestId to instanceId }
            requestIdsByInstance.forEach { (requestId, instanceId) ->
                val requestEvents = beforeClose.filter { it.requestId == requestId }
                assertTrue(requestEvents.isNotEmpty())
                assertTrue(requestEvents.all { it.instanceId == instanceId })
                assertEquals(1, requestEvents.count { it.type == C6EventType.GENERATE_BEGIN })
                assertEquals(1, requestEvents.count { it.type == C6EventType.GENERATE_COMPLETE })
                assertEquals(0, requestEvents.count { it.type == C6EventType.GENERATE_CANCELLED })
                assertEquals(0, requestEvents.count { it.type == C6EventType.GENERATE_FAILURE })
            }

            instances.forEach { managed ->
                closedInstanceIds += managed.instanceId
                managed.instance.close()
            }

            val afterClose = recorder.snapshot()
            val lastCompletion = afterClose.filter { it.type == C6EventType.GENERATE_COMPLETE }.maxOf(C6Event::sequence)
            assertEquals(4, afterClose.count { it.type == C6EventType.CLOSE_SUCCESS })
            instanceIds.forEach { instanceId ->
                val closeSuccess = afterClose.single { it.type == C6EventType.CLOSE_SUCCESS && it.instanceId == instanceId }
                assertTrue(closeSuccess.sequence > lastCompletion)
            }
            recorder.record(
                C6EventType.SCENARIO_PASS,
                accelerator = config.accelerator.name,
                detail = "instances=4 requests=4 barrier=all-ready-then-single-release outputsNonblank=true config=GPU/512/1/0.0/0.0",
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            recorder.record(
                C6EventType.SCENARIO_FAIL,
                accelerator = config.accelerator.name,
                detail = failure.javaClass.simpleName,
            )
            throw failure
        } finally {
            for (managed in instances.asReversed()) {
                if (managed.instanceId in closedInstanceIds) continue
                closedInstanceIds += managed.instanceId
                try {
                    managed.instance.close()
                } catch (cleanupFailure: Throwable) {
                    Log.w(
                        C6EvidenceContract.TAG,
                        "C6E cleanup close failed after ${primaryFailure?.javaClass?.simpleName ?: "successful scenario"}",
                        cleanupFailure,
                    )
                }
            }
            recorder.record(C6EventType.SESSION_END, accelerator = config.accelerator.name)
            recorder.snapshot().forEach { Log.i(C6EvidenceContract.TAG, it.toEvidenceLine()) }
        }
    }

    private data class ManagedInstance(
        val instanceId: String,
        val instance: C6RuntimeInstance,
    )

    private companion object {
        const val SCENARIO_ID = "c6e-multiple-instance-serialization"
        const val PROMPT = "Answer in one short sentence: What is the capital of Japan?"
    }
}
