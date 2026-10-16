package io.github.xiaochuan45.autoledger.capture

import io.github.xiaochuan45.autoledger.core.PaymentChannel
import org.junit.Assert.*
import org.junit.Test

class CompatibilityCheckTest {
    @Test fun alipayImplementationNamespaceIsAcceptedOnlyForAlipayEvents() {
        val probe = CompatibilityCheck { 0 }
        probe.start()
        val name = "com.alipay.mobile.bill.detail.ui.FictionalDetailActivity"
        probe.window(PaymentChannel.WECHAT, name)
        assertTrue(probe.snapshot().windows.isEmpty())
        probe.window(PaymentChannel.ALIPAY, name)
        assertEquals(PaymentChannel.ALIPAY, probe.snapshot().windows.single().channel)
    }

    @Test fun probeRequiresExplicitStartAndExpiresWithoutATimer() {
        var time = 0L
        val probe = CompatibilityCheck { time }
        probe.window(PaymentChannel.WECHAT, "com.tencent.mm.ui.FictionalUI")
        assertFalse(probe.snapshot().active)
        assertTrue(probe.snapshot().windows.isEmpty())
        probe.start()
        probe.window(PaymentChannel.WECHAT, "com.tencent.mm.ui.FictionalUI")
        assertTrue(probe.snapshot().active)
        time = CompatibilityCheck.DURATION_MS
        assertFalse(probe.snapshot().active)
        probe.window(PaymentChannel.WECHAT, "com.tencent.mm.ui.AfterExpiryUI")
        assertEquals(1, probe.snapshot().windows.size)
    }

    @Test fun arbitraryTextUrlsAndOtherApplicationTypesAreNeverRetained() {
        val probe = CompatibilityCheck { 0 }
        probe.start()
        listOf("虚构商户23.50元", "https://example.com/pay?secret=1", "com.tencent.mm.ui.Bad\n付款成功",
            "com.example.OtherUI", "android.widget.TextView", "com.tencent.mm." + "A".repeat(201), "com.tencent.mm.ui.X:secret")
            .forEach { probe.window(PaymentChannel.WECHAT, it) }
        assertTrue(probe.snapshot().windows.isEmpty())
        probe.window(PaymentChannel.WECHAT, "com.tencent.mm.plugin.wallet.ui.FictionalUI")
        assertEquals("com.tencent.mm.plugin.wallet.ui.FictionalUI", probe.snapshot().windows.single().className)
    }

    @Test fun metadataIsBoundedAndRepeatedTypesDoNotGrowTheReport() {
        val probe = CompatibilityCheck { 0 }
        probe.start()
        repeat(100) { probe.window(PaymentChannel.WECHAT, "com.tencent.mm.ui.Fictional${it}UI") }
        assertEquals(CompatibilityCheck.MAX_WINDOWS, probe.snapshot().windows.size)
        repeat(100) { probe.window(PaymentChannel.WECHAT, "com.tencent.mm.ui.Fictional99UI") }
        assertEquals(CompatibilityCheck.MAX_WINDOWS, probe.snapshot().windows.size)
    }

    @Test fun stoppingPreventsCollectionAndClearingRemovesTheLocalReport() {
        val probe = CompatibilityCheck { 0 }
        probe.start()
        probe.window(PaymentChannel.ALIPAY, "com.eg.android.AlipayGphone.FictionalUI")
        probe.stop()
        probe.window(PaymentChannel.ALIPAY, "com.eg.android.AlipayGphone.AnotherUI")
        assertEquals(1, probe.snapshot().windows.size)
        probe.clear()
        assertFalse(probe.snapshot().active)
        assertTrue(probe.snapshot().windows.isEmpty())
        probe.start()
        assertTrue(probe.snapshot().windows.isEmpty())
    }
}
