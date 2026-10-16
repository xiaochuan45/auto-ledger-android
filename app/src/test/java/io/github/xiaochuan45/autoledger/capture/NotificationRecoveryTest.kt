package io.github.xiaochuan45.autoledger.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationRecoveryTest {
    @Test fun disabledCaptureOrMissingAccessCannotAcquireARecoveryAttempt() {
        val gate = NotificationRecoveryGate()
        assertEquals(NotificationRecoveryResult.ACCESS_NOT_GRANTED,
            gate.acquire(false, true, false, true, 0))
        assertEquals(NotificationRecoveryResult.CAPTURE_DISABLED,
            gate.acquire(true, false, false, true, 0))
        // Failed prerequisites do not consume the later, explicitly enabled recovery attempt.
        assertNull(gate.acquire(true, true, false, true, 0))
    }

    @Test fun alreadyConnectedListenerDoesNotAcquireARecoveryAttempt() {
        val gate = NotificationRecoveryGate()
        assertEquals(NotificationRecoveryResult.ALREADY_CONNECTED,
            gate.acquire(true, true, true, true, 0))
        assertNull(gate.acquire(true, true, false, true, 0))
    }

    @Test fun repeatedForegroundAndDisconnectEventsCannotLoopRecovery() {
        val gate = NotificationRecoveryGate()
        assertNull(gate.acquire(true, true, false, true, 0))
        assertEquals(NotificationRecoveryResult.ALREADY_REQUESTED,
            gate.acquire(true, true, false, true, 31_000))
        assertEquals(NotificationRecoveryResult.ALREADY_REQUESTED,
            gate.acquire(true, true, false, true, 3_600_000))
        // Explicit user recovery remains available after the cooldown without a polling loop.
        assertNull(gate.acquire(true, true, false, false, 3_600_000))
    }

    @Test fun rapidManualRequestsAndReconnectFlappingAreThrottled() {
        val gate = NotificationRecoveryGate()
        assertNull(gate.acquire(true, true, false, false, 0))
        assertEquals(NotificationRecoveryResult.THROTTLED,
            gate.acquire(true, true, false, false, 29_999))
        gate.onConnected()
        assertEquals(NotificationRecoveryResult.THROTTLED,
            gate.acquire(true, true, false, true, 29_999))
        assertNull(gate.acquire(true, true, false, true, 30_000))
    }

    @Test fun realConnectionStartsANewRecoveryEpisode() {
        val gate = NotificationRecoveryGate()
        assertNull(gate.acquire(true, true, false, true, 0))
        gate.onConnected()
        assertNull(gate.acquire(true, true, false, true, 30_000))
    }

    @Test fun successfulRequestIsOnlyARequestAndNeverPretendsToBeConnected() {
        CaptureServiceConnections.notificationConnected = false
        val before = CaptureDiagnostics.snapshot().notificationReconnectRequests
        var calls = 0
        val result = NotificationRecovery.submitRequest({ calls++ }, { 123L })
        assertEquals(NotificationRecoveryResult.REQUESTED, result)
        assertEquals(1, calls)
        assertEquals(before + 1, CaptureDiagnostics.snapshot().notificationReconnectRequests)
        assertEquals(123L, CaptureDiagnostics.snapshot().lastNotificationReconnectAt)
        assertFalse(CaptureServiceConnections.notificationConnected)
    }

    @Test fun failedRequestKeepsDisconnectedAndRetainsOnlySafeFailureCode() {
        CaptureServiceConnections.notificationConnected = false
        val before = CaptureDiagnostics.snapshot()
        val result = NotificationRecovery.submitRequest(
            { throw SecurityException("fictional sensitive exception must not be retained") }, { 123L })
        val after = CaptureDiagnostics.snapshot()
        assertEquals(NotificationRecoveryResult.FAILED, result)
        assertEquals(before.notificationReconnectRequests, after.notificationReconnectRequests)
        assertEquals(before.failureCount + 1, after.failureCount)
        assertEquals(CaptureFailure.NOTIFICATION_REBIND, after.lastFailure)
        assertFalse(CaptureServiceConnections.notificationConnected)
        assertFalse(after.toString().contains("fictional sensitive"))
    }
}
