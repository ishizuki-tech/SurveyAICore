package com.negi.surveyaicore.c5harness

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class C5SmokeInstrumentationTest {
    @Test
    fun publicSmoke() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val modelFile = File(targetContext.filesDir, C5SmokeContract.MODEL_FILE_NAME)
        val requireModel =
            InstrumentationRegistry.getArguments().getString("c5.requireModel") == "true"

        if (!modelFile.isFile) {
            val message = "C5 model is not provisioned at ${modelFile.path}"
            if (requireModel) {
                throw AssertionError("$message; c5.requireModel=true")
            }
            Log.i(C5SmokeContract.TAG, "MODEL_MISSING_SKIP")
            assumeTrue("$message; optional-model smoke skipped.", false)
        }

        val report =
            C5SmokeRunner(targetContext).run(
                modelFile = modelFile,
                config = C5SmokeContract.smokeConfig(),
                prompt = C5SmokeContract.PROMPT,
            )

        assertTrue(report.isSuccess)
        assertTrue(report.createSucceeded)
        assertFalse(report.timedOut)
        assertNull(report.errorMessage)
        assertTrue(report.text.isNotBlank())
    }
}
