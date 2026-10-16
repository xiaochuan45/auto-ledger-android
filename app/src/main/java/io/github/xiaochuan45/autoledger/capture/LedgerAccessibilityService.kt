package io.github.xiaochuan45.autoledger.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.xiaochuan45.autoledger.core.CaptureInput
import io.github.xiaochuan45.autoledger.core.CapturePolicy
import io.github.xiaochuan45.autoledger.core.CaptureSource
import io.github.xiaochuan45.autoledger.core.PaymentChannel

class LedgerAccessibilityService : AccessibilityService() {
    private lateinit var preferences: CapturePreferences
    private lateinit var dispatcher: CaptureDispatcher
    private var settingsObserver: AutoCloseable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val windows = PaymentWindowCapture(::readVerifiedWindow, ::scheduleRead, ::cancelScheduledRead)
    private val session get() = windows.session
    private var scheduledRead: Runnable? = null
    private var lastReadElapsed = 0L

    override fun onCreate() {
        super.onCreate()
        preferences = CapturePreferences(this)
        dispatcher = CaptureDispatcher(this)
        settingsObserver = preferences.observeChanges {
            dispatcher.cancelPending()
            clearWindow()
            CaptureDiagnostics.cancelOneShotWechatReceipt()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        CaptureServiceConnections.accessibilityConnected = true
        // Service connection is not a reason to inspect whichever screen is already open.
        clearWindow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        CaptureServiceConnections.accessibilityConnected = true
        val packageName = event.packageName?.toString() ?: run { clearWindow(); return }
        val channel = PaymentChannel.fromPackage(packageName) ?: run { clearWindow(); return }
        if (!preferences.isCaptureEnabled(channel)) { clearWindow(); return }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val className = event.className?.toString().orEmpty()
        val nowElapsed = SystemClock.elapsedRealtime()
        val trustedClass = CapturePolicy.isPaymentWindow(channel, className)
        val stateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        CaptureDiagnostics.windowEvent(channel, className, stateChanged && trustedClass, recordType = stateChanged)
        CaptureDiagnostics.noteOneShotWindow(channel, className)
        if (CaptureDiagnostics.compatibilityActive()) {
            clearWindow()
            CaptureDiagnostics.skipped(CaptureSkip.COMPATIBILITY_ONLY, CaptureSource.ACCESSIBILITY)
            return
        }
        val reason = windows.event(channel, className, event.windowId, stateChanged, nowElapsed,
            explicitReady = CaptureDiagnostics.oneShotSnapshot().state == OneShotState.WAITING)
        if (reason != null) {
            if (session == null) clearWindow()
            CaptureDiagnostics.skipped(reason, CaptureSource.ACCESSIBILITY)
        }
    }

    private fun scheduleRead(current: PaymentWindowSession) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val intervalDelay = (MIN_READ_INTERVAL_MS - (nowElapsed - lastReadElapsed)).coerceAtLeast(0)
        // An asynchronous receipt must finish painting before either automatic or explicit one-time reading.
        // A stream of content events must not postpone this same request forever. No polling is used.
        if (scheduledRead != null) return
        val webDelay = if (current.receipt)
            maxOf(500L, 750L - (nowElapsed - current.verifiedAtElapsed)) else 0L
        val remainingDelay = maxOf(intervalDelay, webDelay)
        scheduledRead?.let(handler::removeCallbacks)
        val read = Runnable {
            scheduledRead = null
            if (session?.token == current.token) readVerifiedWindow(current)
        }
        scheduledRead = read
        handler.postDelayed(read, remainingDelay)
    }

    private fun readVerifiedWindow(expected: PaymentWindowSession) {
        if (CaptureDiagnostics.compatibilityActive()) { clearWindow(); return }
        if (!preferences.isCaptureEnabled(expected.channel) ||
            SystemClock.elapsedRealtime() - expected.verifiedAtElapsed !in 0..PaymentWindowGate.VALIDITY_MS ||
            (expected.explicitOnly && CaptureDiagnostics.oneShotSnapshot().state != OneShotState.WAITING)
        ) { clearWindow(); return }
        if (expected.explicitOnly && !CaptureDiagnostics.beginOneShotWindowCheck(expected.channel, expected.screenClass)) return
        lastReadElapsed = SystemClock.elapsedRealtime()
        try {
            // Never inspect event.source, event.text, other windows, screenshots or event parcel data.
            val root = rootInActiveWindow ?: run {
                if (expected.explicitOnly) CaptureDiagnostics.oneShotStage(OneShotStage.ROOT_MISSING)
                CaptureDiagnostics.skipped(CaptureSkip.ROOT_UNAVAILABLE, CaptureSource.ACCESSIBILITY)
                return
            }
            val validRoot = try {
                root.packageName?.toString() == expected.channel.packageName && root.windowId == expected.windowId
            } catch (_: RuntimeException) {
                releaseNode(root)
                throw IllegalStateException()
            }
            if (!validRoot) {
                if (expected.explicitOnly) CaptureDiagnostics.oneShotStage(OneShotStage.WINDOW_CHANGED)
                releaseNode(root)
                clearWindow()
                CaptureDiagnostics.skipped(CaptureSkip.WINDOW_CHANGED, CaptureSource.ACCESSIBILITY)
                return
            }
            val webReceipt = expected.receipt
            val requested = webReceipt && !expected.explicitOnly &&
                CaptureDiagnostics.takeOneShotWechatReceipt(expected.channel, expected.screenClass)
            val scopedRoot = if (webReceipt) {
                val matched = try { WechatWebReceiptScope.findReceiptRoot(root, expected.channel.packageName, expected.windowId,
                    selectedMini = expected.explicitOnly, requestId = if (expected.explicitOnly || requested)
                        CaptureDiagnostics.oneShotSnapshot().requestId else null) }
                    catch (failure: RuntimeException) { releaseNode(root); throw failure }
                if (matched == null && (!requested || expected.explicitOnly)) {
                    releaseNode(root)
                    if (expected.explicitOnly) CaptureDiagnostics.oneShotStage(OneShotStage.INCOMPLETE)
                    CaptureDiagnostics.skipped(CaptureSkip.WEB_RECEIPT_SCOPE, CaptureSource.ACCESSIBILITY)
                    return
                }
                if (matched != null) {
                    releaseNode(root)
                    CaptureDiagnostics.webReceiptMatched()
                    matched
                } else root // Explicit one-time selection: only this validated app/window, still review-only.
            } else root
            val selected = if (expected.explicitOnly)
                CaptureDiagnostics.takeOneShotWechatReceipt(expected.channel, expected.screenClass) else requested
            if (expected.explicitOnly && !selected) { releaseNode(scopedRoot); clearWindow(); return }
            val texts = PaymentTextReader.read(scopedRoot, expected.channel.packageName, webReceipt, selectedMini = expected.explicitOnly) ?: return
            if (texts.isEmpty()) {
                CaptureDiagnostics.skipped(CaptureSkip.EMPTY_TEXT, CaptureSource.ACCESSIBILITY)
                return
            }
            dispatcher.submit(CaptureInput(
                source = if (selected) CaptureSource.USER_WEB_RECEIPT else CaptureSource.ACCESSIBILITY,
                packageName = expected.channel.packageName,
                texts = texts,
                observedAt = System.currentTimeMillis(),
                sourceKey = "window:${expected.screenClass}:${expected.windowId}:${expected.token}",
                screenClass = expected.screenClass,
            ))
        } catch (_: RuntimeException) {
            CaptureDiagnostics.failed(CaptureFailure.ACCESSIBILITY_READ)
            clearWindow()
        } finally {
            if (expected.explicitOnly && CaptureDiagnostics.oneShotSnapshot().state != OneShotState.WAITING) clearWindow()
        }
    }

    private fun cancelScheduledRead() {
        scheduledRead?.let(handler::removeCallbacks)
        scheduledRead = null
        lastReadElapsed = 0
    }

    private fun clearWindow() { windows.clear() }

    override fun onInterrupt() {
        CaptureDiagnostics.cancelOneShotWechatReceipt()
        CaptureServiceConnections.accessibilityConnected = false
        clearWindow()
        dispatcher.cancelPending()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        CaptureDiagnostics.cancelOneShotWechatReceipt()
        CaptureServiceConnections.accessibilityConnected = false
        clearWindow()
        dispatcher.cancelPending()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        CaptureDiagnostics.cancelOneShotWechatReceipt()
        CaptureServiceConnections.accessibilityConnected = false
        clearWindow()
        settingsObserver?.close()
        dispatcher.close()
        super.onDestroy()
    }

    private companion object {
        const val MIN_READ_INTERVAL_MS = 600L
        @Suppress("DEPRECATION")
        fun releaseNode(node: AccessibilityNodeInfo) { node.recycle() }
    }
}
