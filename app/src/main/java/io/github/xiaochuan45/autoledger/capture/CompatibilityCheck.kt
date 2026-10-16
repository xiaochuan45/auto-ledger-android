package io.github.xiaochuan45.autoledger.capture

import io.github.xiaochuan45.autoledger.core.PaymentChannel

data class CompatibilityWindow(val channel: PaymentChannel, val className: String)
data class CompatibilitySnapshot(val active: Boolean, val windows: List<CompatibilityWindow>, val remainingMillis: Long)

/** Explicit, bounded, process-only metadata probe. Never accepts page text or notification bodies. */
internal class CompatibilityCheck(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var startedAt: Long? = null
    private val windows = ArrayDeque<CompatibilityWindow>()

    @Synchronized fun start() { windows.clear(); startedAt = clock() }
    @Synchronized fun stop() { startedAt = null }
    @Synchronized fun clear() { stop(); windows.clear() }
    @Synchronized fun snapshot(): CompatibilitySnapshot {
        val started = startedAt
        if (started != null && clock() - started !in 0 until DURATION_MS) startedAt = null
        val remaining = startedAt?.let { (DURATION_MS - (clock() - it)).coerceIn(0, DURATION_MS) } ?: 0
        return CompatibilitySnapshot(startedAt != null, windows.toList(), remaining)
    }

    @Synchronized fun window(channel: PaymentChannel, className: String?) {
        if (!snapshot().active || className == null || className.length > 200) return
        // A canonical app-owned Java type only: never retain arbitrary AccessibilityEvent values.
        val ownedNamespace = className.startsWith(channel.packageName + ".") ||
            (channel == PaymentChannel.ALIPAY && className.startsWith("com.alipay."))
        if (!ownedNamespace ||
            !Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+").matches(className)) return
        val value = CompatibilityWindow(channel, className)
        windows.remove(value)
        if (windows.size == MAX_WINDOWS) windows.removeFirst()
        windows.addLast(value)
    }

    companion object { const val DURATION_MS = 300_000L; const val MAX_WINDOWS = 6 }
}
