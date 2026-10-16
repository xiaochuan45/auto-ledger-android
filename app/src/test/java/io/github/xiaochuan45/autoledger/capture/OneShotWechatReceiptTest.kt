package io.github.xiaochuan45.autoledger.capture

import io.github.xiaochuan45.autoledger.core.PaymentChannel
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt
import org.junit.Assert.*
import org.junit.Test

class OneShotWechatReceiptTest {
    @Test fun selectedMiniConsumesExactlyOnceWhileLauncherNeverConsumes() {
        val request = OneShotWechatReceipt { 100L }
        request.arm()
        assertFalse(request.consume(PaymentChannel.WECHAT, "com.tencent.mm.ui.LauncherUI"))
        assertTrue(request.consume(PaymentChannel.WECHAT, WechatWebReceipt.MINI_WINDOW))
        assertFalse(request.consume(PaymentChannel.WECHAT, WechatWebReceipt.MINI_WINDOW))
        assertEquals(1, request.snapshot().attempts)
    }

    @Test fun requestMetadataIsBoundedCanonicalAndSurvivesTimeoutWithoutAnyFieldValues() {
        var now = 100L
        val request = OneShotWechatReceipt { now }
        request.window(PaymentChannel.WECHAT, WechatWebReceipt.MINI_WINDOW)
        request.arm()
        listOf("虚构商户42.70元", "com.tencent.mm.ui.Type\n支付成功", "https://example.invalid/pay")
            .forEach { request.window(PaymentChannel.WECHAT, it) }
        request.window(PaymentChannel.ALIPAY, WechatWebReceipt.MINI_WINDOW)
        assertTrue(request.snapshot().windowTypes.isEmpty())
        repeat(20) { request.window(PaymentChannel.WECHAT, "com.tencent.mm.ui.Fictional${it}UI") }
        assertEquals(6, request.snapshot().windowTypes.size)
        now += 30_000
        assertEquals(OneShotState.EXPIRED, request.snapshot().state)
        assertEquals(6, request.snapshot().windowTypes.size)
        request.arm()
        assertTrue(request.snapshot().windowTypes.isEmpty())
    }
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
