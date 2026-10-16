package io.github.xiaochuan45.autoledger.capture

import io.github.xiaochuan45.autoledger.core.PaymentChannel
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt
import org.junit.Assert.*
import org.junit.Test

class OneShotWechatReceiptTest {
    @Test fun explicitRequestAllowsExactlyOneMatchingWindow() {
        val request = OneShotWechatReceipt { 100L }
        assertFalse(request.consume(PaymentChannel.WECHAT, WechatWebReceipt.WINDOW))
        request.arm()
        assertFalse(request.consume(PaymentChannel.ALIPAY, WechatWebReceipt.WINDOW))
        assertFalse(request.consume(PaymentChannel.WECHAT, "com.tencent.mm.ui.LauncherUI"))
        assertTrue(request.consume(PaymentChannel.WECHAT, WechatWebReceipt.WINDOW))
        assertFalse(request.consume(PaymentChannel.WECHAT, WechatWebReceipt.WINDOW))
        assertEquals(1, request.snapshot().attempts)
        assertEquals(OneShotState.USED, request.snapshot().state)
    }
    @Test fun expiresAtThirtySecondsAndCanBeCancelledOrRestarted() {
        var now = 100L
        val request = OneShotWechatReceipt { now }
        request.arm(); now += 30_000
        assertFalse(request.consume(PaymentChannel.WECHAT, WechatWebReceipt.WINDOW))
        assertEquals(OneShotState.EXPIRED, request.snapshot().state)
        request.arm(); request.cancel()
        assertEquals(OneShotState.CANCELLED, request.snapshot().state)
        assertFalse(request.consume(PaymentChannel.WECHAT, WechatWebReceipt.WINDOW))
        request.arm(); now += 29_999
        assertTrue(request.consume(PaymentChannel.WECHAT, WechatWebReceipt.WINDOW))
    }
    @Test fun processReplacementAndClockRollbackCannotCarryRequest() {
        var now = 100L
        val request = OneShotWechatReceipt { now }
        request.arm(); now = 50
        assertFalse(request.consume(PaymentChannel.WECHAT, WechatWebReceipt.WINDOW))
        assertEquals(OneShotState.NONE, OneShotWechatReceipt { now }.snapshot().state)
    }
}
