package com.negi.surveyaicore

import com.negi.surveyaicore.inference.InferenceClient
import com.negi.surveyaicore.inference.InferenceOutput
import com.negi.surveyaicore.runtime.RuntimeModel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SurveyAICoreTest {
    @Test
    fun generateForwardsPromptModelAndDeltasAndMapsOutput() = runBlocking {
        val client = FakeInferenceClient(
            output = InferenceOutput("partial text", 42L, timedOut = true, error = "timed out"),
        )
        val model = testModel()
        val core = SurveyAICore.createForTesting(client, model)
        val deltas = mutableListOf<String>()

        val result = core.generate("prompt", deltas::add)

        assertSame(model, client.generatedModel)
        assertEquals("prompt", client.generatedPrompt)
        assertEquals(listOf("one", "two"), deltas)
        assertEquals(SurveyAICoreResult("partial text", 42L, true, "timed out"), result)
    }

    @Test
    fun generateUsesNoOpCallbackWhenNoneIsProvided() = runBlocking {
        val client = FakeInferenceClient()
        val core = SurveyAICore.createForTesting(client, testModel())

        core.generate("prompt")

        assertEquals("prompt", client.generatedPrompt)
    }

    @Test
    fun generatePropagatesCancellation() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val client = FakeInferenceClient(generationFailure = cancellation)
        val core = SurveyAICore.createForTesting(client, testModel())

        val actual =
            assertThrowsSuspend(CancellationException::class.java) { core.generate("prompt") }

        assertSame(cancellation, actual)
    }

    @Test
    fun unexpectedClientFailureReleasesAdmissionAndKeepsFacadeReusable() = runBlocking {
        val failure = IllegalStateException("client failed")
        val client = FakeInferenceClient(queuedGenerationFailures = mutableListOf(failure))
        val core = SurveyAICore.createForTesting(client, testModel())

        val actual =
            assertThrowsSuspend(IllegalStateException::class.java) { core.generate("first") }

        assertSame(failure, actual)
        core.generate("second")

        assertEquals(2, client.generateCalls)
        assertEquals(0, client.closeCalls)
        assertCloseCompletesWithin(core)

        assertEquals(1, client.closeCalls)
        assertThrowsSuspend(IllegalStateException::class.java) { core.generate("after-close") }
        Unit
    }

    @Test
    fun callbackFailureReleasesAdmissionAndKeepsFacadeReusable() = runBlocking {
        val callbackFailure = IllegalStateException("callback failed")
        val client = FakeInferenceClient()
        val core = SurveyAICore.createForTesting(client, testModel())

        val actual =
            assertThrowsSuspend(IllegalStateException::class.java) {
                core.generate("first") { throw callbackFailure }
            }

        assertSame(callbackFailure, actual)
        core.generate("second")

        assertEquals(2, client.generateCalls)
        assertEquals(0, client.closeCalls)
        assertCloseCompletesWithin(core)

        assertEquals(1, client.closeCalls)
        assertThrowsSuspend(IllegalStateException::class.java) { core.generate("after-close") }
        Unit
    }

    @Test
    fun cancelledGenerationReleasesAdmissionAndKeepsFacadeReusable() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = FakeInferenceClient(generationEntered = entered, generationRelease = release)
        val core = SurveyAICore.createForTesting(client, testModel())

        val generation = async(start = CoroutineStart.UNDISPATCHED) { core.generate("first") }
        entered.await()

        generation.cancel()
        assertThrowsSuspend(CancellationException::class.java) {
            generation.await()
            Unit
        }
        release.complete(Unit)
        core.generate("second")

        assertEquals(2, client.generateCalls)
        assertEquals(0, client.closeCalls)
        assertCloseCompletesWithin(core)

        assertEquals(1, client.closeCalls)
        assertThrowsSuspend(IllegalStateException::class.java) { core.generate("after-close") }
        Unit
    }

    @Test
    fun closeIsIdempotentAndRejectsFutureGeneration() = runBlocking {
        val client = FakeInferenceClient()
        val core = SurveyAICore.createForTesting(client, testModel())

        core.close()
        core.close()

        assertEquals(1, client.closeCalls)
        assertThrowsSuspend(IllegalStateException::class.java) { core.generate("prompt") }
        assertEquals(0, client.generateCalls)
    }

    @Test
    fun closeWaitsForAdmittedGenerationBeforeClosingClient() = runBlocking {
        val admitted = CompletableDeferred<Unit>()
        val releaseBeforeClient = CompletableDeferred<Unit>()
        val client = FakeInferenceClient()
        val core =
            SurveyAICore.createForTesting(client, testModel()) {
                admitted.complete(Unit)
                releaseBeforeClient.await()
            }

        val generation = async(start = CoroutineStart.UNDISPATCHED) { core.generate("first") }
        admitted.await()
        val close = async(start = CoroutineStart.UNDISPATCHED) { core.close() }

        assertFalse(close.isCompleted)
        assertThrowsSuspend(IllegalStateException::class.java) { core.generate("second") }
        assertEquals(0, client.generateCalls)

        releaseBeforeClient.complete(Unit)
        generation.await()
        close.await()

        assertEquals(1, client.generateCalls)
        assertEquals(1, client.closeCalls)
        assertThrowsSuspend(IllegalStateException::class.java) { core.generate("later") }
        Unit
    }

    @Test
    fun closeWaitsForActiveGenerationThenClosesOnce() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = FakeInferenceClient(generationEntered = entered, generationRelease = release)
        val core = SurveyAICore.createForTesting(client, testModel())

        val generation = async(start = CoroutineStart.UNDISPATCHED) { core.generate("prompt") }
        entered.await()
        val close = async(start = CoroutineStart.UNDISPATCHED) { core.close() }

        assertFalse(close.isCompleted)
        release.complete(Unit)
        generation.await()
        close.await()

        assertEquals(1, client.closeCalls)
    }

    @Test
    fun closeWaitsForClientCleanupCompletion() = runBlocking {
        val closeEntered = CompletableDeferred<Unit>()
        val closeRelease = CompletableDeferred<Unit>()
        val client =
            FakeInferenceClient(
                closeEntered = closeEntered,
                closeRelease = closeRelease,
            )
        val core = SurveyAICore.createForTesting(client, testModel())

        val close = async(start = CoroutineStart.UNDISPATCHED) { core.close() }
        closeEntered.await()

        assertFalse(close.isCompleted)
        closeRelease.complete(Unit)
        close.await()

        assertEquals(1, client.closeCalls)
    }

    @Test
    fun concurrentCloseCallersShareOneCompletion() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = FakeInferenceClient(generationEntered = entered, generationRelease = release)
        val core = SurveyAICore.createForTesting(client, testModel())

        val generation = async(start = CoroutineStart.UNDISPATCHED) { core.generate("prompt") }
        entered.await()
        val firstClose = async(start = CoroutineStart.UNDISPATCHED) { core.close() }
        val secondClose = async(start = CoroutineStart.UNDISPATCHED) { core.close() }

        assertFalse(firstClose.isCompleted)
        assertFalse(secondClose.isCompleted)
        release.complete(Unit)
        generation.await()
        firstClose.await()
        secondClose.await()

        assertEquals(1, client.closeCalls)
    }

    @Test
    fun cancellingCloseWaiterDoesNotInterruptOwnerShutdown() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = FakeInferenceClient(generationEntered = entered, generationRelease = release)
        val core = SurveyAICore.createForTesting(client, testModel())

        val generation = async(start = CoroutineStart.UNDISPATCHED) { core.generate("prompt") }
        entered.await()
        val owner = async(start = CoroutineStart.UNDISPATCHED) { core.close() }

        assertThrowsSuspend(IllegalStateException::class.java) { core.generate("during-close") }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { core.close() }

        assertFalse(owner.isCompleted)
        assertFalse(waiter.isCompleted)
        waiter.cancel()
        assertThrowsSuspend(CancellationException::class.java) {
            waiter.await()
            Unit
        }
        assertFalse(owner.isCompleted)
        assertEquals(0, client.closeCalls)

        release.complete(Unit)
        generation.await()
        owner.await()

        assertEquals(1, client.closeCalls)
        assertThrowsSuspend(IllegalStateException::class.java) { core.generate("after-close") }
        Unit
    }

    @Test
    fun cancelledGenerationReleasesCloseDrain() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = FakeInferenceClient(generationEntered = entered, generationRelease = release)
        val core = SurveyAICore.createForTesting(client, testModel())

        val generation = async(start = CoroutineStart.UNDISPATCHED) { core.generate("prompt") }
        entered.await()
        val close = async(start = CoroutineStart.UNDISPATCHED) { core.close() }

        generation.cancel()
        generation.join()
        close.await()

        assertEquals(1, client.closeCalls)
        assertThrowsSuspend(IllegalStateException::class.java) { core.generate("later") }
        release.complete(Unit)
        Unit
    }

    @Test
    fun cancelledCloseInitiatorStillCompletesShutdown() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = FakeInferenceClient(generationEntered = entered, generationRelease = release)
        val core = SurveyAICore.createForTesting(client, testModel())

        val generation = async(start = CoroutineStart.UNDISPATCHED) { core.generate("prompt") }
        entered.await()
        val close = async(start = CoroutineStart.UNDISPATCHED) { core.close() }

        close.cancel()
        release.complete(Unit)
        generation.await()
        close.join()

        assertEquals(1, client.closeCalls)
        assertThrowsSuspend(IllegalStateException::class.java) { core.generate("later") }
        Unit
    }

    @Test
    fun launchedCloseFromDeltaDoesNotDeadlock() = runBlocking {
        val client = FakeInferenceClient()
        lateinit var core: SurveyAICore
        val closeFinished = CompletableDeferred<Unit>()
        val closeLaunched = CompletableDeferred<Unit>()
        core = SurveyAICore.createForTesting(client, testModel())

        core.generate("prompt") {
            if (closeLaunched.complete(Unit)) {
                this@runBlocking.launch {
                    core.close()
                    closeFinished.complete(Unit)
                }
            }
        }

        closeLaunched.await()
        closeFinished.await()
        assertEquals(1, client.closeCalls)
    }

    @Test
    fun closeFailureIsTerminalAndShared() = runBlocking {
        val failure = IllegalStateException("close failed")
        val client = FakeInferenceClient(closeFailure = failure)
        val core = SurveyAICore.createForTesting(client, testModel())

        val first = assertThrowsSuspend(IllegalStateException::class.java) { core.close() }
        val second = assertThrowsSuspend(IllegalStateException::class.java) { core.close() }

        assertEquals(failure.message, first.message)
        assertEquals(failure.message, second.message)
        assertEquals(1, client.closeCalls)
        assertThrowsSuspend(IllegalStateException::class.java) { core.generate("later") }
        Unit
    }

    @Test
    fun concurrentCloseCallersShareTerminalFailure() = runBlocking {
        supervisorScope {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val failure = IllegalStateException("close failed")
            val client =
                FakeInferenceClient(
                    generationEntered = entered,
                    generationRelease = release,
                    closeFailure = failure,
                )
            val core = SurveyAICore.createForTesting(client, testModel())

            val generation = async(start = CoroutineStart.UNDISPATCHED) { core.generate("prompt") }
            entered.await()
            val owner = async(start = CoroutineStart.UNDISPATCHED) { core.close() }
            assertThrowsSuspend(IllegalStateException::class.java) { core.generate("during-close") }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { core.close() }

            assertFalse(owner.isCompleted)
            assertFalse(waiter.isCompleted)
            release.complete(Unit)
            generation.await()

            val ownerFailure =
                assertThrowsSuspend(IllegalStateException::class.java) {
                    owner.await()
                    Unit
                }
            val waiterFailure =
                assertThrowsSuspend(IllegalStateException::class.java) {
                    waiter.await()
                    Unit
                }
            val futureFailure =
                assertThrowsSuspend(IllegalStateException::class.java) { core.close() }

            assertEquals(failure.message, ownerFailure.message)
            assertEquals(failure.message, waiterFailure.message)
            assertEquals(failure.message, futureFailure.message)
            assertEquals(1, client.closeCalls)
            assertThrowsSuspend(IllegalStateException::class.java) { core.generate("after-close") }
        }
        Unit
    }

    @Test
    fun validateModelFileRejectsMissingDirectoryAndEmptyFiles() {
        val directory = Files.createTempDirectory("survey-ai-core").toFile()
        val missing = File(directory, "missing.model")
        val empty = File.createTempFile("survey-ai-core", ".model")

        try {
            assertThrows(IllegalArgumentException::class.java) { validateModelFile(missing) }
            assertThrows(IllegalArgumentException::class.java) { validateModelFile(directory) }
            assertThrows(IllegalArgumentException::class.java) { validateModelFile(empty) }
        } finally {
            assertTrue(empty.delete())
            assertTrue(directory.delete())
        }
    }

    @Test
    fun validateModelFileReturnsCanonicalNonEmptyReadableFile() {
        val file = File.createTempFile("survey-ai-core", ".model")
        file.writeText("model")

        try {
            assertEquals(file.canonicalFile, validateModelFile(file))
        } finally {
            assertTrue(file.delete())
        }
    }

    private fun assertCloseCompletesWithin(
        core: SurveyAICore,
        timeoutSeconds: Long = 5,
    ) {
        val closeFinished = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>(null)
        val closeThread =
            Thread(
                {
                    try {
                        runBlocking { core.close() }
                    } catch (failure: Throwable) {
                        closeFailure.set(failure)
                    } finally {
                        closeFinished.countDown()
                    }
                },
                "SurveyAICore-close-test",
            ).apply {
                isDaemon = true
            }

        closeThread.start()
        assertTrue(
            "SurveyAICore.close() did not complete within $timeoutSeconds seconds",
            closeFinished.await(timeoutSeconds, TimeUnit.SECONDS),
        )
        closeThread.join()
        assertFalse("SurveyAICore close thread is still alive", closeThread.isAlive)
        closeFailure.get()?.let { failure ->
            throw AssertionError("SurveyAICore.close() failed", failure)
        }
    }

    private fun testModel() = RuntimeModel(name = "test", taskPath = "/tmp/test.model")

    private fun <T : Throwable> assertThrows(expectedType: Class<T>, block: () -> Unit): T {
        try {
            block()
        } catch (actual: Throwable) {
            if (expectedType.isInstance(actual)) return expectedType.cast(actual)!!
            throw actual
        }
        fail("Expected ${expectedType.simpleName}")
        throw AssertionError("unreachable")
    }

    private suspend fun <T : Throwable> assertThrowsSuspend(
        expectedType: Class<T>,
        block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (actual: Throwable) {
            if (expectedType.isInstance(actual)) return expectedType.cast(actual)!!
            throw actual
        }
        fail("Expected ${expectedType.simpleName}")
        throw AssertionError("unreachable")
    }

    private class FakeInferenceClient(
        private val output: InferenceOutput = InferenceOutput("", 1L),
        private val generationFailure: Throwable? = null,
        private val queuedGenerationFailures: MutableList<Throwable> = mutableListOf(),
        private val generationEntered: CompletableDeferred<Unit>? = null,
        private val generationRelease: CompletableDeferred<Unit>? = null,
        private val closeEntered: CompletableDeferred<Unit>? = null,
        private val closeRelease: CompletableDeferred<Unit>? = null,
        private val closeFailure: Throwable? = null,
    ) : InferenceClient {
        var generatedModel: RuntimeModel? = null
        var generatedPrompt: String? = null
        var generateCalls = 0
        var closeCalls = 0

        override suspend fun initialize(model: RuntimeModel): Result<Unit> = Result.success(Unit)

        override suspend fun generate(
            model: RuntimeModel,
            prompt: String,
            onDelta: (String) -> Unit,
        ): InferenceOutput {
            generatedModel = model
            generatedPrompt = prompt
            generateCalls++
            generationEntered?.complete(Unit)
            generationRelease?.await()
            if (queuedGenerationFailures.isNotEmpty()) {
                throw queuedGenerationFailures.removeAt(0)
            }
            generationFailure?.let { throw it }
            onDelta("one")
            onDelta("two")
            return output
        }

        override suspend fun reset(model: RuntimeModel): Result<Unit> = Result.success(Unit)

        override suspend fun close(model: RuntimeModel?) {
            closeCalls++
            closeEntered?.complete(Unit)
            closeRelease?.await()
            closeFailure?.let { throw it }
        }
    }
}
