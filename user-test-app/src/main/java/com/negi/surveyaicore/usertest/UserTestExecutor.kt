package com.negi.surveyaicore.usertest

import android.content.Context
import com.negi.surveyaicore.SurveyAICore
import com.negi.surveyaicore.SurveyAICoreConfig
import com.negi.surveyaicore.SurveyAIFollowup
import com.negi.surveyaicore.SurveyAIFollowupOutcome
import com.negi.surveyaicore.SurveyAIFollowupRequest
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-local seam for UI tests; the production implementation uses only public Core APIs. */
internal interface UserTestExecutor {
    suspend fun initialize(modelFile: File): Result<Unit>

    suspend fun advance(request: SurveyAIFollowupRequest): SurveyAIFollowupOutcome

    suspend fun close(): Result<Unit>
}

internal class PublicCoreUserTestExecutor(
    context: Context,
) : UserTestExecutor {
    private val appContext = context.applicationContext ?: context
    private val sessionMutex = Mutex()
    private var core: SurveyAICore? = null
    private var followup: SurveyAIFollowup? = null
    private var closeCompletion: CompletableDeferred<Result<Unit>>? = null

    override suspend fun initialize(modelFile: File): Result<Unit> =
        resultOf {
            sessionMutex.withLock {
                check(core == null && closeCompletion == null) { "Core is already initialized or closing" }
                val created = SurveyAICore.create(appContext, modelFile, SurveyAICoreConfig())
                core = created
                followup = SurveyAIFollowup.from(created)
            }
        }

    override suspend fun advance(request: SurveyAIFollowupRequest): SurveyAIFollowupOutcome =
        sessionMutex.withLock {
            checkNotNull(followup) { "Initialize Core before evaluating" }
        }.advance(request)

    override suspend fun close(): Result<Unit> =
        when (val plan = beginClose()) {
            ClosePlan.NoCore -> Result.success(Unit)
            is ClosePlan.Waiter -> plan.completion.await()
            is ClosePlan.Owner -> {
                val result =
                    try {
                        withContext(NonCancellable) { plan.core.close() }
                        Result.success(Unit)
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                plan.completion.complete(result)
                sessionMutex.withLock {
                    if (closeCompletion === plan.completion) {
                        closeCompletion = null
                    }
                }
                result
            }
        }

    private suspend fun beginClose(): ClosePlan =
        sessionMutex.withLock {
            closeCompletion?.let { return@withLock ClosePlan.Waiter(it) }
            val ownedCore = core ?: return@withLock ClosePlan.NoCore
            val completion = CompletableDeferred<Result<Unit>>()
            core = null
            followup = null
            closeCompletion = completion
            ClosePlan.Owner(ownedCore, completion)
        }

    private suspend fun <T> resultOf(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }

    private sealed interface ClosePlan {
        data object NoCore : ClosePlan

        data class Owner(
            val core: SurveyAICore,
            val completion: CompletableDeferred<Result<Unit>>,
        ) : ClosePlan

        data class Waiter(
            val completion: CompletableDeferred<Result<Unit>>,
        ) : ClosePlan
    }
}
