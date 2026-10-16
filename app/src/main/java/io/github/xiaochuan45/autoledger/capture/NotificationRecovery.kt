package io.github.xiaochuan45.autoledger.capture

enum class NotificationRecoveryResult {
    REQUESTED,
    ALREADY_CONNECTED,
    ACCESS_NOT_GRANTED,
    CAPTURE_DISABLED,
    THROTTLED,
    ALREADY_REQUESTED,
    FAILED,
}

/** Pure policy: a foreground visit may request recovery once per disconnected episode. */
internal class NotificationRecoveryGate(private val minimumIntervalMillis: Long = 30_000L) {
    private var automaticAttempted = false
    private var lastRequestElapsed: Long? = null

    /** Null means the caller may issue one official request; it never means connected. */
    @Synchronized fun acquire(
        notificationAccess: Boolean,
        captureEnabled: Boolean,
        connected: Boolean,
        automatic: Boolean,
        elapsedRealtime: Long,
    ): NotificationRecoveryResult? {
        if (!notificationAccess) return NotificationRecoveryResult.ACCESS_NOT_GRANTED
        if (!captureEnabled) return NotificationRecoveryResult.CAPTURE_DISABLED
        if (connected) {
            onConnected()
            return NotificationRecoveryResult.ALREADY_CONNECTED
        }
        if (automatic && automaticAttempted) return NotificationRecoveryResult.ALREADY_REQUESTED
        val previous = lastRequestElapsed
        if (previous != null && elapsedRealtime - previous < minimumIntervalMillis) {
            return NotificationRecoveryResult.THROTTLED
        }
        lastRequestElapsed = elapsedRealtime
        if (automatic) automaticAttempted = true
        return null
    }

    @Synchronized fun onConnected() { automaticAttempted = false }
}

internal object NotificationRecovery {
    val gate = NotificationRecoveryGate()

    /** No state is optimistically marked connected; only a later system callback can do that. */
    fun submitRequest(requestRebind: () -> Unit, requestedAt: () -> Long): NotificationRecoveryResult = try {
        requestRebind()
        CaptureDiagnostics.notificationReconnectRequested(requestedAt())
        NotificationRecoveryResult.REQUESTED
    } catch (_: RuntimeException) {
        CaptureDiagnostics.failed(CaptureFailure.NOTIFICATION_REBIND)
        NotificationRecoveryResult.FAILED
    }
}
