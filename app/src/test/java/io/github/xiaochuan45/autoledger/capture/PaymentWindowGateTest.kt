package io.github.xiaochuan45.autoledger.capture

import io.github.xiaochuan45.autoledger.core.PaymentChannel
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt
import org.junit.Assert.*
import org.junit.Test

class PaymentWindowGateTest {
    private val wechat = PaymentChannel.WECHAT
    private val mini = WechatWebReceipt.MINI_WINDOW
    private val main = "com.tencent.mm.ui.LauncherUI"

    @Test fun merchantMiniIsRejectedAutomaticallyAndNeedsAnExplicitRequest() {
        val gate = PaymentWindowGate { "fictional-session" }
        assertEquals(CaptureSkip.WINDOW_UNSUPPORTED, gate.event(wechat, mini, 8, true, 100, false))
        assertEquals(CaptureSkip.WINDOW_UNVERIFIED, gate.event(wechat, "android.view.View", 8, false, 110, false))
        assertNull(gate.session)
        assertNull(gate.event(wechat, mini, 8, true, 120, true))
        assertTrue(gate.session!!.explicitOnly)
        assertEquals(mini, gate.session!!.screenClass)
    }

    @Test fun sameWindowChildStatesDoNotDestroySelectedReceiptButLauncherAlwaysDoes() {
        val gate = PaymentWindowGate { "fictional-session" }
        gate.event(wechat, mini, 8, true, 100, true)
        val original = gate.session
        assertNull(gate.event(wechat, "android.widget.FrameLayout", 8, true, 110, true))
        assertNull(gate.event(wechat, "com.tencent.mm.ui.widget.FictionalView", 8, false, 120, true))
        assertEquals(original, gate.session)
        assertEquals(CaptureSkip.WINDOW_UNSUPPORTED, gate.event(wechat, main, 8, false, 130, true))
        assertNull(gate.session)
        assertEquals(CaptureSkip.WINDOW_UNSUPPORTED, gate.event(wechat, main, 8, true, 140, true))
    }

    @Test fun genericContentCannotCreateTrustAndOnlyExactSelectedHostMayRecoverAMissedStateEvent() {
        val gate = PaymentWindowGate()
        assertEquals(CaptureSkip.WINDOW_UNVERIFIED, gate.event(wechat, "android.view.View", 8, false, 100, true))
        assertNull(gate.event(wechat, mini, 8, false, 110, true))
        assertEquals(mini, gate.session!!.screenClass)
        gate.clear()
        assertEquals(CaptureSkip.WINDOW_UNVERIFIED, gate.event(wechat, mini, 8, false, 120, false))
    }

    @Test fun requestExpiryWindowChangeAndMissingWindowCannotReuseMiniSession() {
        val gate = PaymentWindowGate()
        gate.event(wechat, mini, 8, true, 100, true)
        assertEquals(CaptureSkip.WINDOW_UNVERIFIED, gate.event(wechat, "android.view.View", 8, false, 120, false))
        assertNull(gate.session)
        gate.event(wechat, mini, 8, true, 130, true)
        assertEquals(CaptureSkip.WINDOW_UNVERIFIED, gate.event(wechat, "android.view.View", 9, false, 140, true))
        assertNull(gate.session)
        assertEquals(CaptureSkip.WINDOW_ID_MISSING, gate.event(wechat, mini, -1, true, 150, true))
        assertNull(gate.session)
    }

    @Test fun nativeAutomaticPathStillRequiresStateEventAndRejectsChildState() {
        val gate = PaymentWindowGate()
        val native = "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI"
        assertEquals(CaptureSkip.WINDOW_UNVERIFIED, gate.event(wechat, native, 8, false, 100, true))
        assertNull(gate.event(wechat, native, 8, true, 110, false))
        assertFalse(gate.session!!.receipt)
        assertEquals(CaptureSkip.WINDOW_UNSUPPORTED, gate.event(wechat, "android.widget.FrameLayout", 8, true, 120, false))
        assertNull(gate.session)
    }

    @Test fun channelTimeAndReadOnlyClearDoNotCarryTrustIntoLaterEvents() {
        val gate = PaymentWindowGate()
        gate.event(wechat, WechatWebReceipt.WINDOW, 8, true, 100, false)
        assertEquals(CaptureSkip.WINDOW_UNVERIFIED, gate.event(PaymentChannel.ALIPAY, "android.view.View", 8, false, 120, true))
        gate.event(wechat, mini, 8, true, 130, true)
        assertEquals(CaptureSkip.WINDOW_UNVERIFIED, gate.event(wechat, "android.view.View", 8, false, 45_131, true))
        gate.event(wechat, mini, 8, true, 200, true)
        assertEquals(CaptureSkip.WINDOW_UNVERIFIED, gate.event(wechat, "android.view.View", 8, false, 199, true))
        gate.event(wechat, mini, 8, true, 300, true)
        gate.clear()
        assertEquals(CaptureSkip.WINDOW_UNVERIFIED, gate.event(wechat, "android.view.View", 8, false, 310, true))
    }
}
