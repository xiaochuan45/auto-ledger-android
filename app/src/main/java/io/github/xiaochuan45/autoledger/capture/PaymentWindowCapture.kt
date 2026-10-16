package io.github.xiaochuan45.autoledger.capture

import io.github.xiaochuan45.autoledger.core.PaymentChannel

/** One production event-to-read path, shared with regression tests. Never queues a selected mini read. */
internal class PaymentWindowCapture(
    private val immediate: (PaymentWindowSession) -> Unit,
    private val deferred: (PaymentWindowSession) -> Unit,
    private val invalidatePending: () -> Unit,
) {
    private val gate = PaymentWindowGate()
    val session get() = gate.session
    fun clear() { gate.clear(); invalidatePending() }
    fun event(channel: PaymentChannel, className: String, windowId: Int, stateChanged: Boolean,
        now: Long, explicitReady: Boolean): CaptureSkip? {
        val previous = session?.token
        val reason = gate.event(channel, className, windowId, stateChanged, now, explicitReady)
        if (previous != session?.token) invalidatePending()
        if (reason != null) return reason
        val current = session ?: return CaptureSkip.WINDOW_UNVERIFIED
        // Handle the verified mini host while that very callback is still executing.
        // Later Launcher/child/window events cannot silently cancel an unexecuted delayed mini task.
        if (current.explicitOnly) immediate(current) else deferred(current)
        return null
    }
}
