package io.github.xiaochuan45.autoledger.capture

import io.github.xiaochuan45.autoledger.core.PaymentChannel
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt
import org.junit.Assert.*
import org.junit.Test

class PaymentWindowCaptureTest {
    private val channel = PaymentChannel.WECHAT
    private val mini = WechatWebReceipt.MINI_WINDOW
    private val launcher = "com.tencent.mm.ui.LauncherUI"

    @Test fun acceptedMiniChecksInlineBeforeAnImmediateLauncherEventCanCancelAnything() {
        val order = mutableListOf<String>()
        val capture = PaymentWindowCapture({ order += "checked-now" }, { fail("Mini must never be queued") }, { order += "cancel-pending" })
        assertNull(capture.event(channel, mini, 8, true, 100, true))
        assertEquals(listOf("cancel-pending", "checked-now"), order)
        assertEquals(CaptureSkip.WINDOW_UNSUPPORTED, capture.event(channel, launcher, 8, true, 101, true))
        assertNull(capture.session)
        assertEquals(1, order.count { it == "checked-now" })
    }

    @Test fun loadingDoesNotConsumeBodyAndLaterHostCanCheckAgainAfterInvalidation() {
        var now = 100L
        val request = OneShotWechatReceipt { now }.apply { arm() }
        var complete = false; var bodies = 0; var columns = 0
        val capture = PaymentWindowCapture({ session ->
            if (request.beginWindowCheck(session.channel, session.screenClass)) {
                columns++
                if (complete && request.consume(session.channel, session.screenClass)) bodies++
                else request.noteStage(OneShotStage.INCOMPLETE)
            }
        }, { fail("No deferred mini callback") }, {})
        capture.event(channel, mini, 8, true, now, true)
        assertEquals(1, columns); assertEquals(0, bodies)
        assertEquals(OneShotState.WAITING, request.snapshot().state)
        capture.event(channel, launcher, 8, true, now + 1, true)
        now += 501; complete = true
        capture.event(channel, mini, 8, false, now, true)
        assertEquals(2, columns); assertEquals(1, bodies)
        capture.event(channel, mini, 8, false, now + 1, false)
        assertEquals(1, bodies)
    }

    @Test fun eventStormChecksAreBoundedAndNeitherSwitchingHostsNorClearingResetsTheBudget() {
        var now = 100L
        val request = OneShotWechatReceipt { now }.apply { arm() }
        var checks = 0
        val capture = PaymentWindowCapture({ s -> if (request.beginWindowCheck(s.channel, s.screenClass)) checks++ }, {}, {})
        repeat(100) {
            capture.event(channel, mini, 8, true, now, true)
            capture.clear()
            now += 500
        }
        assertEquals(8, checks)
        assertEquals(0, request.snapshot().attempts)
        assertEquals(8, request.snapshot().windowChecks)
        assertEquals(OneShotState.EXPIRED, request.snapshot().state)
    }

    @Test fun automaticMiniLauncherAndUnverifiedContentNeverInvokeEitherReader() {
        val capture = PaymentWindowCapture({ fail("No root read") }, { fail("No scheduled read") }, {})
        assertNotNull(capture.event(channel, mini, 8, true, 100, false))
        assertNotNull(capture.event(channel, launcher, 8, true, 101, true))
        assertNotNull(capture.event(channel, "android.view.View", 8, false, 102, true))
        assertNotNull(capture.event(channel, mini, -1, true, 103, true))
    }

    @Test fun ordinaryNativeAndWebPathsRemainDeferredAndWindowSwitchCancelsThem() {
        var deferred = 0; var cancelled = 0
        val capture = PaymentWindowCapture({ fail("No inline automatic path") }, { deferred++ }, { cancelled++ })
        assertNull(capture.event(channel, "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI", 8, true, 100, false))
        assertEquals(1, deferred)
        assertNotNull(capture.event(channel, launcher, 8, true, 101, false))
        assertEquals(2, cancelled)
        assertNull(capture.event(channel, WechatWebReceipt.WINDOW, 9, true, 102, false))
        assertEquals(2, deferred)
    }
}
