package com.negi.surveyaicore

import android.content.Context
import com.negi.surveyaicore.inference.InferenceClient
import com.negi.surveyaicore.inference.LiteRtInferenceClient
import com.negi.surveyaicore.runtime.RuntimeModel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SurveyAICore private constructor(
    private val client: InferenceClient,
    private val model: RuntimeModel,
    private val beforeClientGenerate: suspend () -> Unit = {},
) {
    private val lifecycleMutex = Mutex()
    private var lifecycleState = LifecycleState.READY
    private var activeGenerationCount = 0
    private var drainCompletion: CompletableDeferred<Unit>? = null
    private var closeCompletion: CompletableDeferred<Unit>? = null

    suspend fun generate(
        prompt: String,
        onDelta: ((String) -> Unit)? = null,
    ): SurveyAICoreResult {
        admitGeneration()
        try {
            beforeClientGenerate()
            return client.generate(model, prompt, onDelta ?: {}).toSurveyAICoreResult()
        } finally {
            withContext(NonCancellable) {
                releaseGeneration()
            }
        }
    }

    suspend fun close() {
        when (val plan = beginClose()) {
            is ClosePlan.Owner -> completeClose(plan)
            is ClosePlan.Waiter -> plan.completion.await()
        }
    }

    private suspend fun admitGeneration() {
        lifecycleMutex.withLock {
            check(lifecycleState == LifecycleState.READY) { "SurveyAICore is closed" }
            activeGenerationCount++
        }
    }

    private suspend fun releaseGeneration() {
        lifecycleMutex.withLock {
            check(activeGenerationCount > 0) { "Generation admission count underflow" }
            activeGenerationCount--
            if (lifecycleState == LifecycleState.CLOSING && activeGenerationCount == 0) {
                drainCompletion?.complete(Unit)
            }
        }
    }

    private suspend fun beginClose(): ClosePlan =
        lifecycleMutex.withLock {
            val existingCompletion = closeCompletion
            if (existingCompletion != null) {
                return@withLock ClosePlan.Waiter(existingCompletion)
            }

            lifecycleState = LifecycleState.CLOSING
            val completion = CompletableDeferred<Unit>()
            closeCompletion = completion
            val drain =
                if (activeGenerationCount == 0) {
                    null
                } else {
                    CompletableDeferred<Unit>().also { drainCompletion = it }
                }
            ClosePlan.Owner(completion, drain)
        }

    private suspend fun completeClose(plan: ClosePlan.Owner) {
        withContext(NonCancellable) {
            var failure: Throwable? = null
            try {
                plan.drain?.await()
                client.close(model)
            } catch (error: Throwable) {
                failure = error
            } finally {
                lifecycleMutex.withLock {
                    lifecycleState = LifecycleState.CLOSED
                    drainCompletion = null
                }
                if (failure == null) {
                    plan.completion.complete(Unit)
                } else {
                    plan.completion.completeExceptionally(failure)
                }
            }
        }
        plan.completion.await()
    }

    private enum class LifecycleState {
        READY,
        CLOSING,
        CLOSED,
    }

    private sealed interface ClosePlan {
        data class Owner(
            val completion: CompletableDeferred<Unit>,
            val drain: CompletableDeferred<Unit>?,
        ) : ClosePlan

        data class Waiter(
            val completion: CompletableDeferred<Unit>,
        ) : ClosePlan
    }

    companion object {
        suspend fun create(
            context: Context,
            modelFile: File,
            config: SurveyAICoreConfig = SurveyAICoreConfig(),
        ): SurveyAICore {
            val appContext = context.applicationContext ?: context
            val canonicalModelFile = validateModelFile(modelFile)
            val model =
                RuntimeModel(
                    name = canonicalModelFile.name,
                    taskPath = canonicalModelFile.path,
                    config = config.toRuntimeConfig(),
                )

            LiteRtNativeLoader.ensureLoaded()
            val client: InferenceClient = LiteRtInferenceClient(appContext)
            client.initialize(model).exceptionOrNull()?.let { failure ->
                client.close(null)
                throw failure
            }
            return SurveyAICore(client, model)
        }

        internal fun createForTesting(
            client: InferenceClient,
            model: RuntimeModel,
            beforeClientGenerate: suspend () -> Unit = {},
        ): SurveyAICore = SurveyAICore(client, model, beforeClientGenerate)
    }
}

internal fun validateModelFile(modelFile: File): File {
    val canonicalModelFile =
        try {
            modelFile.canonicalFile
        } catch (error: IOException) {
            throw IllegalArgumentException("Model file path cannot be resolved", error)
        }

    require(canonicalModelFile.exists()) { "Model file does not exist" }
    require(canonicalModelFile.isFile) { "Model path is not a regular file" }
    require(canonicalModelFile.canRead()) { "Model file is not readable" }
    require(canonicalModelFile.length() > 0L) { "Model file is empty" }
    return canonicalModelFile
}
