package com.negi.surveyaicore.c6harness

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test-only lifecycle owner. It never starts inference until a future test supplies work. */
class C6LifecycleActivity : Activity() {
    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(activityJob + Dispatchers.Main.immediate)
    private var ownedWork: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    internal fun launchOwnedWorkForTest(
        close: suspend () -> Unit,
        work: suspend () -> Unit,
    ): Job {
        check(ownedWork == null) { "C6LifecycleActivity already owns active work" }
        return activityScope.launch(Dispatchers.Default) {
            try {
                work()
            } finally {
                withContext(NonCancellable) {
                    close()
                }
            }
        }.also { ownedWork = it }
    }

    override fun onDestroy() {
        activityJob.cancel()
        super.onDestroy()
    }
}
