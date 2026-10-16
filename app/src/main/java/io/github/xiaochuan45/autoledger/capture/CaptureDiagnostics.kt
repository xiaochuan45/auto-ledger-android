package io.github.xiaochuan45.autoledger.capture

import io.github.xiaochuan45.autoledger.core.CaptureSource
import io.github.xiaochuan45.autoledger.core.PaymentChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class CaptureFailure {
    NOTIFICATION_READ, ACCESSIBILITY_READ, PARSE_OR_STORAGE, SYSTEM_SETTINGS,
    SKIPPED_INCOMPLETE, QUEUE_FULL,
    NOTIFICATION_REBIND,
}

/** Process-local diagnostics only. No source text, title, merchant, amount or exception is retained. */
data class CaptureDiagnosticSnapshot(
    val observedCount: Long = 0,
    val parsedCount: Long = 0,
    val failureCount: Long = 0,
    val lastChannel: PaymentChannel? = null,
    val lastSource: CaptureSource? = null,
    val lastObservedAt: Long? = null,
    val lastFailure: CaptureFailure? = null,
    val notificationReconnectRequests: Long = 0,
    val lastNotificationReconnectAt: Long? = null,
)

object CaptureDiagnostics {
    private var current = CaptureDiagnosticSnapshot()

    @Synchronized fun snapshot(): CaptureDiagnosticSnapshot = current

    @Synchronized internal fun observed(channel: PaymentChannel, source: CaptureSource, at: Long) {
        current = current.copy(
            observedCount = current.observedCount.incrementSafely(),
            lastChannel = channel,
            lastSource = source,
            lastObservedAt = at,
        )
    }

    @Synchronized internal fun parsed() {
        current = current.copy(parsedCount = current.parsedCount.incrementSafely())
    }

    @Synchronized internal fun failed(reason: CaptureFailure) {
        current = current.copy(failureCount = current.failureCount.incrementSafely(), lastFailure = reason)
    }

    @Synchronized internal fun notificationReconnectRequested(at: Long) {
        current = current.copy(
            notificationReconnectRequests = current.notificationReconnectRequests.incrementSafely(),
            lastNotificationReconnectAt = at,
        )
    }

    private fun Long.incrementSafely(): Long = if (this == Long.MAX_VALUE) this else this + 1
}

/** Binding is an observation in this process, not a promise that Android will keep it running. */
data class CaptureConnectionSnapshot(
    val notificationConnected: Boolean = false,
    val accessibilityConnected: Boolean = false,
)

internal object CaptureServiceConnections {
    private val mutableState = MutableStateFlow(CaptureConnectionSnapshot())
    val changes = mutableState.asStateFlow()

    var notificationConnected: Boolean
        get() = mutableState.value.notificationConnected
        set(value) { mutableState.update { it.copy(notificationConnected = value) } }

    var accessibilityConnected: Boolean
        get() = mutableState.value.accessibilityConnected
        set(value) { mutableState.update { it.copy(accessibilityConnected = value) } }
}
