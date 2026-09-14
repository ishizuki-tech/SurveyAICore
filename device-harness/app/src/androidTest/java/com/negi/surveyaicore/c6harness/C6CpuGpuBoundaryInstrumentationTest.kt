package com.negi.surveyaicore.c6harness

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.surveyaicore.SurveyAICoreAccelerator
import com.negi.surveyaicore.SurveyAICoreConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Validates the public CPU-to-GPU configuration boundary using fresh instances. */
@RunWith(AndroidJUnit4::class)
class C6CpuGpuBoundaryInstrumentationTest {
    @Test
    fun cpuThenGpuBoundary(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = C6EventRecorder(scenarioId = SCENARIO_ID)
        val runner = C6RuntimeRunner(context, recorder)
        val modelFile = File(context.filesDir, C6EvidenceContract.MODEL_FILE_NAME)
        var cpuInstance: C6RuntimeInstance? = null
        var gpuInstance: C6RuntimeInstance? = null
        var cpuCloseAttempted = false
        var gpuCloseAttempted = false
        var primaryFailure: Throwable? = null

        recorder.record(C6EventType.SESSION_BEGIN)
        recorder.record(C6EventType.SCENARIO_BEGIN, detail = "order=CPU->GPU")
        try {
            cpuInstance = runner.create(CPU_INSTANCE_ID, modelFile, cpuConfig())
            val cpuResult = cpuInstance.launchGenerate(this, PROMPT).result.await()
            assertNormalResult("CPU", cpuResult)

            cpuCloseAttempted = true
            cpuInstance.close()

            gpuInstance = runner.create(GPU_INSTANCE_ID, modelFile, gpuConfig())
            val gpuResult = gpuInstance.launchGenerate(this, PROMPT).result.await()
            assertNormalResult("GPU", gpuResult)

            gpuCloseAttempted = true
            gpuInstance.close()

            val events = recorder.snapshot()
            val requestIds = listOf(cpuResult.requestId, gpuResult.requestId)
            assertEquals("C6F requires two unique request IDs", 2, requestIds.toSet().size)
            assertEquals(2, events.count { it.type == C6EventType.CREATE_SUCCESS })
            assertEquals(2, events.count { it.type == C6EventType.GENERATE_BEGIN })
            assertEquals(2, events.count { it.type == C6EventType.GENERATE_COMPLETE })
            assertEquals(2, events.count { it.type == C6EventType.CLOSE_SUCCESS })
            assertTrue(events.none { it.type == C6EventType.GENERATE_CANCELLED })
            assertTrue(events.none { it.type == C6EventType.GENERATE_FAILURE })
            assertTrue(events.none { it.type == C6EventType.CANCEL_REQUESTED })
            assertTrue(events.none { it.type == C6EventType.CANCEL_OBSERVED })
            assertTrue(events.none { it.type == C6EventType.LATE_CALLBACK })

            assertRequestIntent(events, CPU_INSTANCE_ID, cpuResult.requestId, SurveyAICoreAccelerator.CPU)
            assertRequestIntent(events, GPU_INSTANCE_ID, gpuResult.requestId, SurveyAICoreAccelerator.GPU)

            val cpuCreate = events.single { it.type == C6EventType.CREATE_SUCCESS && it.instanceId == CPU_INSTANCE_ID }
            val cpuBegin = events.single { it.type == C6EventType.GENERATE_BEGIN && it.requestId == cpuResult.requestId }
            val cpuComplete = events.single { it.type == C6EventType.GENERATE_COMPLETE && it.requestId == cpuResult.requestId }
            val cpuClose = events.single { it.type == C6EventType.CLOSE_SUCCESS && it.instanceId == CPU_INSTANCE_ID }
            val gpuCreate = events.single { it.type == C6EventType.CREATE_SUCCESS && it.instanceId == GPU_INSTANCE_ID }
            val gpuBegin = events.single { it.type == C6EventType.GENERATE_BEGIN && it.requestId == gpuResult.requestId }
            val gpuComplete = events.single { it.type == C6EventType.GENERATE_COMPLETE && it.requestId == gpuResult.requestId }
            val gpuClose = events.single { it.type == C6EventType.CLOSE_SUCCESS && it.instanceId == GPU_INSTANCE_ID }
            assertTrue(cpuCreate.sequence < cpuBegin.sequence)
            assertTrue(cpuBegin.sequence < cpuComplete.sequence)
            assertTrue(cpuComplete.sequence < cpuClose.sequence)
            assertTrue(cpuClose.sequence < gpuCreate.sequence)
            assertTrue(gpuCreate.sequence < gpuBegin.sequence)
            assertTrue(gpuBegin.sequence < gpuComplete.sequence)
            assertTrue(gpuComplete.sequence < gpuClose.sequence)

            recorder.record(
                C6EventType.SCENARIO_PASS,
                detail = "requests=2 order=CPU->GPU outputsNonblank=true requestedAccelerators=CPU,GPU",
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            recorder.record(C6EventType.SCENARIO_FAIL, detail = failure.javaClass.simpleName)
            throw failure
        } finally {
            if (gpuInstance != null && !gpuCloseAttempted) {
                gpuCloseAttempted = true
                try {
                    gpuInstance.close()
                } catch (cleanupFailure: Throwable) {
                    Log.w(
                        C6EvidenceContract.TAG,
                        "C6F GPU cleanup close failed after ${primaryFailure?.javaClass?.simpleName ?: "successful scenario"}",
                        cleanupFailure,
                    )
                }
            }
            if (cpuInstance != null && !cpuCloseAttempted) {
                cpuCloseAttempted = true
                try {
                    cpuInstance.close()
                } catch (cleanupFailure: Throwable) {
                    Log.w(
                        C6EvidenceContract.TAG,
                        "C6F CPU cleanup close failed after ${primaryFailure?.javaClass?.simpleName ?: "successful scenario"}",
                        cleanupFailure,
                    )
                }
            }
            recorder.record(C6EventType.SESSION_END)
            recorder.snapshot().forEach { Log.i(C6EvidenceContract.TAG, it.toEvidenceLine()) }
        }
    }

    private fun assertNormalResult(label: String, result: C6RequestResult) {
        assertEquals("$label request must complete", C6RequestTerminalState.COMPLETED, result.terminalState)
        assertFalse("$label request must not time out", result.timedOut)
        assertNull("$label request must not report an error", result.errorMessage)
        assertFalse("$label request must not request cancellation", result.cancelRequested)
        assertFalse("$label request must not observe cancellation", result.cancelObserved)
        assertTrue("$label request output must be nonblank", result.text.isNotBlank())
    }

    private fun assertRequestIntent(
        events: List<C6Event>,
        instanceId: String,
        requestId: Long,
        accelerator: SurveyAICoreAccelerator,
    ) {
        val expected = accelerator.name
        val instanceEvents = events.filter { it.instanceId == instanceId }
        assertTrue(instanceEvents.isNotEmpty())
        assertTrue(instanceEvents.all { it.accelerator == expected })
        val requestEvents = events.filter { it.requestId == requestId }
        assertTrue(requestEvents.isNotEmpty())
        assertTrue(requestEvents.all { it.instanceId == instanceId && it.accelerator == expected })
        assertEquals(1, requestEvents.count { it.type == C6EventType.GENERATE_BEGIN })
        assertEquals(1, requestEvents.count { it.type == C6EventType.GENERATE_COMPLETE })
        assertEquals(0, requestEvents.count { it.type == C6EventType.GENERATE_CANCELLED })
        assertEquals(0, requestEvents.count { it.type == C6EventType.GENERATE_FAILURE })
    }

    private fun cpuConfig(): SurveyAICoreConfig =
        SurveyAICoreConfig(
            accelerator = SurveyAICoreAccelerator.CPU,
            maxTokens = 512,
            topK = 1,
            topP = 0.0f,
            temperature = 0.0f,
        )

    private fun gpuConfig(): SurveyAICoreConfig =
        SurveyAICoreConfig(
            accelerator = SurveyAICoreAccelerator.GPU,
            maxTokens = 512,
            topK = 1,
            topP = 0.0f,
            temperature = 0.0f,
        )

    private companion object {
        const val SCENARIO_ID = "c6f-cpu-gpu-boundary"
        const val CPU_INSTANCE_ID = "c6f-cpu-instance"
        const val GPU_INSTANCE_ID = "c6f-gpu-instance"
        const val PROMPT = "Answer in one short sentence: What is the capital of Japan?"
    }
}
