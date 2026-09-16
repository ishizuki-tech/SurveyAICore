package com.negi.surveyaicore.c6harness

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** C6A-only recorder verification; it never creates SurveyAICore or reads a model. */
@RunWith(AndroidJUnit4::class)
class C6FoundationInstrumentationTest {
    @Test
    fun eventRecorderKeepsHarnessIdentitiesOrderedAndFilterable() {
        val recorder = C6EventRecorder(sessionId = "session-c6a", scenarioId = "foundation")
        recorder.record(C6EventType.SESSION_BEGIN)
        val firstRequest = recorder.allocateRequestId()
        val secondRequest = recorder.allocateRequestId()
        recorder.record(C6EventType.GENERATE_BEGIN, instanceId = "instance-a", requestId = firstRequest)
        recorder.record(C6EventType.GENERATE_COMPLETE, instanceId = "instance-a", requestId = firstRequest)
        recorder.record(C6EventType.GENERATE_BEGIN, instanceId = "instance-b", requestId = secondRequest)

        val all = recorder.snapshot()
        assertEquals(listOf(1L, 2L, 3L, 4L), all.map(C6Event::sequence))
        assertEquals(1L, firstRequest)
        assertEquals(2L, secondRequest)
        assertEquals(2, recorder.eventsForRequest(firstRequest).size)
        assertEquals(2, recorder.eventsForInstance("instance-a").size)
        assertTrue(all.all { it.elapsedRealtimeMs >= 0L })

        all.forEach { Log.i(C6EvidenceContract.TAG, it.toEvidenceLine()) }
    }
}
