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
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt
import java.util.ArrayDeque
import java.util.UUID

class LedgerAccessibilityService : AccessibilityService() {
    private lateinit var preferences: CapturePreferences
    private lateinit var dispatcher: CaptureDispatcher
    private var settingsObserver: AutoCloseable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var session: PaymentWindowSession? = null
    private var scheduledRead: Runnable? = null
    private var lastReadElapsed = 0L

    override fun onCreate() {
        super.onCreate()
        preferences = CapturePreferences(this)
        dispatcher = CaptureDispatcher(this)
        settingsObserver = preferences.observeChanges {
            dispatcher.cancelPending()
            clearWindow()
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
        val webCandidate = WechatWebReceipt.isCandidate(channel, className)
        val stateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        CaptureDiagnostics.windowEvent(channel, className, stateChanged && trustedClass, recordType = stateChanged)
        if (CaptureDiagnostics.compatibilityActive()) {
            clearWindow()
            CaptureDiagnostics.skipped(CaptureSkip.COMPATIBILITY_ONLY, CaptureSource.ACCESSIBILITY)
            return
        }
        if (event.windowId < 0) {
            clearWindow()
            CaptureDiagnostics.skipped(CaptureSkip.WINDOW_ID_MISSING, CaptureSource.ACCESSIBILITY)
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Entering any unsupported page/dialog invalidates a previously verified payment window.
            if (!trustedClass && !webCandidate) {
                clearWindow()
                CaptureDiagnostics.skipped(CaptureSkip.WINDOW_UNSUPPORTED, CaptureSource.ACCESSIBILITY)
                return
            }
            val current = session
            if (current == null || current.channel != channel || current.windowId != event.windowId ||
                current.screenClass != className || nowElapsed - current.verifiedAtElapsed > SESSION_VALIDITY_MS
            ) {
                clearWindow()
                session = PaymentWindowSession(channel, className, event.windowId,
                    UUID.randomUUID().toString(), nowElapsed)
            } else {
                session = current.copy(verifiedAtElapsed = nowElapsed)
            }
        } else {
            val current = session ?: run {
                CaptureDiagnostics.skipped(CaptureSkip.WINDOW_UNVERIFIED, CaptureSource.ACCESSIBILITY)
                return
            }
            if (current.channel != channel || current.windowId != event.windowId ||
                nowElapsed - current.verifiedAtElapsed > SESSION_VALIDITY_MS
            ) { clearWindow(); return }
            // Content event classes are usually TextView/FrameLayout. They cannot establish trust.
            if ((trustedClass || webCandidate) && className != current.screenClass) { clearWindow(); return }
        }

        val current = session ?: return
        val remainingDelay = (MIN_READ_INTERVAL_MS - (nowElapsed - lastReadElapsed)).coerceAtLeast(0)
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
            SystemClock.elapsedRealtime() - expected.verifiedAtElapsed > SESSION_VALIDITY_MS
        ) { clearWindow(); return }
        lastReadElapsed = SystemClock.elapsedRealtime()
        try {
            // Never inspect event.source, event.text, other windows, screenshots or event parcel data.
            val root = rootInActiveWindow ?: run {
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
                releaseNode(root)
                clearWindow()
                CaptureDiagnostics.skipped(CaptureSkip.WINDOW_CHANGED, CaptureSource.ACCESSIBILITY)
                return
            }
            val webReceipt = WechatWebReceipt.isCandidate(expected.channel, expected.screenClass)
            val scopedRoot = if (webReceipt) {
                val matched = try {
                    WechatWebReceiptScope.findReceiptRoot(root, expected.channel.packageName, expected.windowId)
                } finally { releaseNode(root) }
                if (matched == null) {
                    CaptureDiagnostics.skipped(CaptureSkip.WEB_RECEIPT_SCOPE, CaptureSource.ACCESSIBILITY)
                    return
                }
                CaptureDiagnostics.webReceiptMatched()
                matched
            } else root
            val texts = collectPaymentText(scopedRoot, expected.channel.packageName, if (webReceipt) 48 else 24) ?: return
            if (texts.isEmpty()) {
                CaptureDiagnostics.skipped(CaptureSkip.EMPTY_TEXT, CaptureSource.ACCESSIBILITY)
                return
            }
            dispatcher.submit(CaptureInput(
                source = CaptureSource.ACCESSIBILITY,
                packageName = expected.channel.packageName,
                texts = texts,
                observedAt = System.currentTimeMillis(),
                sourceKey = "window:${expected.screenClass}:${expected.windowId}:${expected.token}",
                screenClass = expected.screenClass,
            ))
        } catch (_: RuntimeException) {
            CaptureDiagnostics.failed(CaptureFailure.ACCESSIBILITY_READ)
            clearWindow()
        }
    }

    /** Takes ownership of root and releases every obtained node, including exceptional paths. */
    private fun collectPaymentText(root: AccessibilityNodeInfo, expectedPackage: String, fragmentLimit: Int): List<String>? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val texts = TransientTextBuffer(fragmentLimit)
        var visited = 0
        queue.add(root)
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES && !texts.incomplete) {
                val node = queue.removeFirst()
                visited++
                try {
                    val nodePackage = node.packageName?.toString()
                    if (nodePackage != null && nodePackage != expectedPackage) continue
                    // Skip editable/password subtrees before reading their text or descriptions.
                    if (!node.isVisibleToUser || node.isPassword || node.isEditable ||
                        node.className?.toString()?.contains("EditText", ignoreCase = true) == true) continue
                    val text = node.text
                    val description = node.contentDescription
                    // A payment authorization/verification view is not a receipt. Discard the sample.
                    if (hasSensitiveText(text) || hasSensitiveText(description)) {
                        CaptureDiagnostics.skipped(CaptureSkip.SENSITIVE_GUARD, CaptureSource.ACCESSIBILITY)
                        return null
                    }
                    texts.add(text)
                    texts.add(description)
                    val available = MAX_NODES - visited - queue.size
                    val childCount = node.childCount
                    if (childCount > available) {
                        // Omitted nodes may contain a failure/discount/status correction.
                        texts.markIncomplete()
                        continue
                    }
                    for (index in 0 until childCount) {
                        val child = node.getChild(index)
                        if (child == null) {
                            // A changing tree is also incomplete; never commit its partial snapshot.
                            texts.markIncomplete()
                            break
                        }
                        queue.addLast(child)
                    }
                } finally {
                    releaseNode(node)
                }
            }
            if (queue.isNotEmpty()) texts.markIncomplete()
            if (texts.incomplete) {
                CaptureDiagnostics.failed(CaptureFailure.SKIPPED_INCOMPLETE)
                return null
            }
            return texts.toList()
        } finally {
            while (queue.isNotEmpty()) releaseNode(queue.removeFirst())
        }
    }

    private fun clearWindow() {
        scheduledRead?.let(handler::removeCallbacks)
        scheduledRead = null
        session = null
        lastReadElapsed = 0
    }

    override fun onInterrupt() {
        CaptureServiceConnections.accessibilityConnected = false
        clearWindow()
        dispatcher.cancelPending()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        CaptureServiceConnections.accessibilityConnected = false
        clearWindow()
        dispatcher.cancelPending()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        CaptureServiceConnections.accessibilityConnected = false
        clearWindow()
        settingsObserver?.close()
        dispatcher.close()
        super.onDestroy()
    }

    private data class PaymentWindowSession(
        val channel: PaymentChannel,
        val screenClass: String,
        val windowId: Int,
        val token: String,
        val verifiedAtElapsed: Long,
    )

    private companion object {
        const val MAX_NODES = 150
        const val MIN_READ_INTERVAL_MS = 600L
        const val SESSION_VALIDITY_MS = 45_000L
        @Suppress("DEPRECATION")
        fun releaseNode(node: AccessibilityNodeInfo) { node.recycle() }
    }
}
