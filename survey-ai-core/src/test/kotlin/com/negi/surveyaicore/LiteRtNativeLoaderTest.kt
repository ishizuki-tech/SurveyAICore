package com.negi.surveyaicore

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LiteRtNativeLoaderTest {
    @Test
    fun successfulLoadsUseTheExpectedLibraryNameOnlyOnce() {
        val calls = AtomicInteger(0)
        var receivedName: String? = null
        val gate = NativeLibraryLoadGate("litertlm_jni") { libraryName ->
            receivedName = libraryName
            calls.incrementAndGet()
        }

        gate.ensureLoaded()
        gate.ensureLoaded()
        gate.ensureLoaded()

        assertEquals("litertlm_jni", receivedName)
        assertEquals(1, calls.get())
    }

    @Test
    fun loadFailurePropagatesWithoutBeingCached() {
        val calls = AtomicInteger(0)
        val expected = UnsatisfiedLinkError("missing JNI")
        val gate = NativeLibraryLoadGate("litertlm_jni") {
            if (calls.incrementAndGet() == 1) throw expected
        }

        try {
            gate.ensureLoaded()
            fail("Expected UnsatisfiedLinkError")
        } catch (actual: UnsatisfiedLinkError) {
            assertSame(expected, actual)
        }

        gate.ensureLoaded()
        gate.ensureLoaded()

        assertEquals(2, calls.get())
    }

    @Test
    fun concurrentSuccessfulLoadsInvokeTheUnderlyingLoaderOnce() {
        val calls = AtomicInteger(0)
        val callersReady = CountDownLatch(2)
        val start = CountDownLatch(1)
        val firstLoadStarted = CountDownLatch(1)
        val allowFirstLoadToFinish = CountDownLatch(1)
        val gate = NativeLibraryLoadGate("litertlm_jni") {
            calls.incrementAndGet()
            firstLoadStarted.countDown()
            assertTrue(allowFirstLoadToFinish.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val tasks = List(2) {
                executor.submit {
                    callersReady.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    gate.ensureLoaded()
                }
            }

            assertTrue(callersReady.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(firstLoadStarted.await(5, TimeUnit.SECONDS))
            allowFirstLoadToFinish.countDown()

            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
