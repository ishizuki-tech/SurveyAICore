package com.negi.surveyaicore.c6harness

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.surveyaicore.SurveyAICoreAccelerator
import com.negi.surveyaicore.SurveyAICoreConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** C6B performs one public GPU create, then exactly three sequential requests on that instance. */
@RunWith(AndroidJUnit4::class)
class C6WarmReuseInstrumentationTest {
    @Test
    fun warmReuseSequential(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = C6EventRecorder(scenarioId = SCENARIO_ID)
        val instanceId = "c6b-warm-reuse-instance-1"
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

            val request1 = instance.launchGenerate(this, PROMPT).result.await()
            val request2 = instance.launchGenerate(this, PROMPT).result.await()
            val request3 = instance.launchGenerate(this, PROMPT).result.await()
            val requests = listOf(request1, request2, request3)

            requests.forEachIndexed { index, request ->
                assertEquals("request ${index + 1} must complete", C6RequestTerminalState.COMPLETED, request.terminalState)
                assertFalse("request ${index + 1} must not time out", request.timedOut)
                assertNull("request ${index + 1} must not report an error", request.errorMessage)
                assertTrue("request ${index + 1} output must be nonblank", request.text.isNotBlank())
            }
            assertEquals("C6B requires three distinct harness request IDs", 3, requests.map(C6RequestResult::requestId).toSet().size)

            val beforeClose = recorder.snapshot()
            assertEquals(1, beforeClose.count { it.type == C6EventType.CREATE_SUCCESS && it.instanceId == instanceId })
            assertEquals(3, beforeClose.count { it.type == C6EventType.GENERATE_BEGIN && it.instanceId == instanceId })
            assertEquals(3, beforeClose.count { it.type == C6EventType.GENERATE_COMPLETE && it.instanceId == instanceId })
            assertEquals(requests.map(C6RequestResult::requestId), beforeClose.filter { it.type == C6EventType.GENERATE_BEGIN }.mapNotNull(C6Event::requestId))
            assertTrue(beforeClose.none { it.type == C6EventType.CLOSE_BEGIN || it.type == C6EventType.CLOSE_SUCCESS })

            closeAttempted = true
            instance.close()

            val afterClose = recorder.snapshot()
            val lastCompletion = afterClose.filter { it.type == C6EventType.GENERATE_COMPLETE }.maxOf(C6Event::sequence)
            val closeSuccess = afterClose.single { it.type == C6EventType.CLOSE_SUCCESS && it.instanceId == instanceId }
            assertTrue("C6B closes only after request 3 completes", closeSuccess.sequence > lastCompletion)
            recorder.record(
                C6EventType.SCENARIO_PASS,
                instanceId = instanceId,
                accelerator = config.accelerator.name,
                detail = "requests=3 requestIds=${requests.joinToString(",") { it.requestId.toString() }} outputsNonblank=true config=GPU/512/1/0.0/0.0",
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

    private companion object {
        const val SCENARIO_ID = "c6b-warm-reuse"
        const val PROMPT = "Answer in one short sentence: What is the capital of Japan?"
    }
}
