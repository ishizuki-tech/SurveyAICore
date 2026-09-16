package com.negi.surveyaicore.c5harness

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(activityJob + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)
        val modelStatus = findViewById<TextView>(R.id.model_status)
        val result = findViewById<TextView>(R.id.smoke_result)
        val runButton = findViewById<Button>(R.id.run_smoke)
        val modelFile = File(applicationContext.filesDir, C5SmokeContract.MODEL_FILE_NAME)

        modelStatus.text = modelStatusText(modelFile)
        status.text = getString(R.string.status_ready)

        runButton.setOnClickListener {
            if (!modelFile.isFile) {
                status.text = getString(R.string.status_model_missing)
                result.text = getString(R.string.result_model_missing)
                return@setOnClickListener
            }

            runButton.isEnabled = false
            status.text = getString(R.string.status_running)
            result.text = ""
            activityScope.launch {
                try {
                    val report =
                        withContext(Dispatchers.Default) {
                            C5SmokeRunner(applicationContext).run(
                                modelFile = modelFile,
                                config = C5SmokeContract.smokeConfig(),
                                prompt = C5SmokeContract.PROMPT,
                            )
                        }
                    status.text =
                        getString(
                            if (report.isSuccess) R.string.status_pass else R.string.status_fail,
                        )
                    result.text = getString(
                        R.string.result_report,
                        report.deltaCount,
                        report.durationMs,
                        report.timedOut,
                        report.errorMessage ?: getString(R.string.no_error),
                        report.text,
                    )
                } catch (error: CancellationException) {
                    Log.i(C5SmokeContract.TAG, "Activity smoke request cancelled")
                } catch (error: Throwable) {
                    Log.e(C5SmokeContract.TAG, "Activity smoke request failed", error)
                    status.text = getString(R.string.status_fail)
                    result.text = getString(
                        R.string.result_exception,
                        error.javaClass.simpleName,
                        error.message ?: getString(R.string.no_message),
                    )
                } finally {
                    if (!isFinishing && !isDestroyed) {
                        runButton.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        activityJob.cancel()
        super.onDestroy()
    }

    private fun modelStatusText(modelFile: File): String =
        getString(
            if (modelFile.isFile) R.string.model_present else R.string.model_missing,
            modelFile.path,
        )
}
