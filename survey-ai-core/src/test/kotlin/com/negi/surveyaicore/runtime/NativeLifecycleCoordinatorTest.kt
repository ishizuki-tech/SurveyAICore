package com.negi.surveyaicore.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLifecycleCoordinatorTest {
    @Test
    fun engineCreationLeaseIsAcquiredAndReleasedByExactLease() {
        val coordinator = NativeLifecycleCoordinator()
        val identity = Any()

        val result = coordinator.acquireEngineCreationLease(identity)

        val lease = (result as NativeLifecycleCoordinator.EngineLeaseResult.Acquired).lease
        assertTrue(coordinator.snapshot().hasEngineCreationLease)
        assertTrue(coordinator.failEngineCreationBeforeNativeState(lease))
        assertFalse(coordinator.snapshot().hasEngineCreationLease)
    }

    @Test
    fun secondEngineCreationWithDifferentIdentityIsRejected() {
        val coordinator = NativeLifecycleCoordinator()
        val first = coordinator.acquireEngineCreationLease(Any())
        val second = coordinator.acquireEngineCreationLease(Any())

        assertTrue(first is NativeLifecycleCoordinator.EngineLeaseResult.Acquired)
        val rejected = second as NativeLifecycleCoordinator.EngineLeaseResult.Rejected
        assertEquals(
            NativeLifecycleCoordinator.EngineLeaseRejection.ANOTHER_CREATION_IN_PROGRESS,
            rejected.reason,
        )
    }

    @Test
    fun teardownRequiresMatchingIdentityAndAllCloseConfirmations() {
        val coordinator = NativeLifecycleCoordinator()
        val runtime = Any()
        val conversation = Any()
        val engine = Any()
        val metadata = NativeLifecycleCoordinator.TeardownMetadata(
            runtimeIdentity = runtime,
            closeIdentities = listOf(conversation, engine),
        )
        val started = coordinator.startTeardown(
            NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
            metadata,
        ) as NativeLifecycleCoordinator.TeardownStartResult.Started

        assertFalse(coordinator.confirmClose(started.flight, Any()))
        assertTrue(coordinator.confirmClose(started.flight, conversation))
        assertNull(coordinator.completeFullTeardown(started.flight))
        val signal = coordinator.confirmClose(started.flight, engine)
        assertTrue(signal)

        val completion = coordinator.completeFullTeardown(started.flight)
        assertNotNull(completion)
        assertEquals(
            NativeLifecycleCoordinator.Status.HEALTHY,
            coordinator.snapshot().status,
        )
    }

    @Test
    fun sameRuntimeJoinsExistingFlightButDifferentRuntimeIsBusy() {
        val coordinator = NativeLifecycleCoordinator()
        val runtime = Any()
        val started = coordinator.startTeardown(
            NativeLifecycleCoordinator.TeardownMode.CONVERSATION_REPLACEMENT,
            NativeLifecycleCoordinator.TeardownMetadata(runtime, listOf(Any())),
        ) as NativeLifecycleCoordinator.TeardownStartResult.Started

        val same = coordinator.startTeardown(
            NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
            NativeLifecycleCoordinator.TeardownMetadata(runtime, listOf(Any())),
        )
        val other = coordinator.startTeardown(
            NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
            NativeLifecycleCoordinator.TeardownMetadata(Any(), listOf(Any())),
        )

        assertSame(
            started.flight,
            (same as NativeLifecycleCoordinator.TeardownStartResult.ExistingSameRuntime).flight,
        )
        assertTrue(other is NativeLifecycleCoordinator.TeardownStartResult.BusyOtherRuntime)
    }

    @Test
    fun unconfirmedClosePoisonsRuntimeAndNotifiesOnce() {
        val coordinator = NativeLifecycleCoordinator()
        val started = coordinator.startTeardown(
            NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
            NativeLifecycleCoordinator.TeardownMetadata(Any(), listOf(Any())),
        ) as NativeLifecycleCoordinator.TeardownStartResult.Started

        val signal = coordinator.poisonIfCloseUnconfirmed(started.flight, started.flight.metadata.closeIdentities[0])

        assertNotNull(signal)
        assertTrue(signal!!.notifyWaiters())
        assertFalse(signal.notifyWaiters())
        assertEquals(
            NativeLifecycleCoordinator.Status.POISONED,
            coordinator.snapshot().status,
        )
        assertNotNull(coordinator.poisonErrorOrNull())
    }
}
