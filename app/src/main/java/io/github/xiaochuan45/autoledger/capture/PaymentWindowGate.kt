package io.github.xiaochuan45.autoledger.capture

import io.github.xiaochuan45.autoledger.core.CapturePolicy
import io.github.xiaochuan45.autoledger.core.PaymentChannel
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt
import java.util.UUID

internal data class PaymentWindowSession(val channel: PaymentChannel, val screenClass: String, val windowId: Int,
    val token: String, val verifiedAtElapsed: Long) {
    val receipt: Boolean get() = WechatWebReceipt.isExplicitCandidate(channel, screenClass)
    val explicitOnly: Boolean get() = WechatWebReceipt.isMiniCandidate(channel, screenClass)
}

/** Metadata only. No root, text, notification, preferences or Android callbacks are accepted here. */
internal class PaymentWindowGate(private val token: () -> String = { UUID.randomUUID().toString() }) {
    var session: PaymentWindowSession? = null
        private set

    fun clear() { session = null }

    fun event(channel: PaymentChannel, className: String, windowId: Int, stateChanged: Boolean,
        now: Long, explicitReady: Boolean): CaptureSkip? {
        if (windowId < 0) { clear(); return CaptureSkip.WINDOW_ID_MISSING }
        val old = session
        if (old != null && (old.channel != channel || old.windowId != windowId ||
                now - old.verifiedAtElapsed !in 0..VALIDITY_MS || (old.explicitOnly && !explicitReady))) clear()
        val trusted = CapturePolicy.isPaymentWindow(channel, className)
        val candidate = trusted || WechatWebReceipt.isCandidate(channel, className) ||
            (explicitReady && WechatWebReceipt.isMiniCandidate(channel, className))
        if (stateChanged || (session == null && explicitReady && WechatWebReceipt.isExplicitCandidate(channel, className))) {
            if (!candidate) {
                // A child widget's state event must not destroy a verified, review-only receipt session.
                // Native auto-confirm paths retain the stricter invalidation rule.
                if (session?.receipt == true && className in CHILD_CLASSES) return null
                clear(); return CaptureSkip.WINDOW_UNSUPPORTED
            }
            val current = session
            session = if (current?.screenClass == className) current.copy(verifiedAtElapsed = now)
                else PaymentWindowSession(channel, className, windowId, token(), now)
            return null
        }
        val current = session ?: return CaptureSkip.WINDOW_UNVERIFIED
        if (candidate && className != current.screenClass) { clear(); return CaptureSkip.WINDOW_CHANGED }
        // A concrete application page class can invalidate a session even if delivered as a content event.
        if (className in FORBIDDEN_CONTENT_CLASSES && className != current.screenClass) {
            clear(); return CaptureSkip.WINDOW_UNSUPPORTED
        }
        return null
    }

    companion object {
        const val VALIDITY_MS = 45_000L
        private val CHILD_CLASSES = setOf("android.view.View", "android.view.ViewGroup",
            "android.widget.FrameLayout", "android.widget.LinearLayout", "android.widget.TextView", "android.webkit.WebView")
        private val FORBIDDEN_CONTENT_CLASSES = setOf("com.tencent.mm.ui.LauncherUI",
            "com.tencent.mm.plugin.appbrand.launching.AppBrandLaunchProxyUI", WechatWebReceipt.MINI_WINDOW)
    }
}
