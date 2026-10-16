package io.github.xiaochuan45.autoledger.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entirely fictional receipts. No real transactions, notifications or screenshots are used. */
class PaymentParserTest {
    private val observedAt = 1_792_152_000_000L
    private val paymentClass = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI"

    private fun notification(body: String, key: String = "fictional-notification:1", title: String = "微信支付") = CaptureInput(
        source = CaptureSource.NOTIFICATION,
        packageName = PaymentChannel.WECHAT.packageName,
        texts = listOf(body),
        observedAt = observedAt,
        sourceKey = key,
        title = title,
    )

    private fun page(body: String, key: String = "fictional-window-session") = CaptureInput(
        source = CaptureSource.ACCESSIBILITY,
        packageName = PaymentChannel.WECHAT.packageName,
        texts = listOf(body),
        observedAt = observedAt,
        sourceKey = key,
        screenClass = paymentClass,
    )

    private fun parsed(input: CaptureInput): ParsedPayment = requireNotNull(PaymentParser.parse(input))

    @Test fun `confirmed receipt uses actual amount instead of total or discount`() {
        val value = parsed(notification("支付成功\n订单总价：30.00元\n优惠金额：6.50元\n实付金额：23.50元\n商户：虚构青禾餐厅\n消费平台：美团\n微信支付交易单号：WXFICTION202610160001\n付款时间：2026-10-16 12:30:09"))
        assertEquals(2350L, value.amountMinor)
        assertEquals(RecordStatus.CONFIRMED, value.status)
        assertEquals("虚构青禾餐厅", value.merchant)
        assertEquals("美团", value.platform)
        assertEquals(PaymentChannel.WECHAT, value.channel)
        assertEquals("WXFICTION202610160001", value.transactionId)
        assertEquals(TimeSource.PAYMENT, value.timeSource)
        assertEquals(LocalDateTime.of(2026, 10, 16, 12, 30, 9).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), value.occurredAt)
        assertNull(value.reviewReason)
    }

    @Test fun `unknown merchant platform and payment time stay unknown`() {
        val value = parsed(notification("付款成功\n付款金额：23.50元"))
        assertEquals(RecordStatus.CONFIRMED, value.status)
        assertEquals(TimeSource.OBSERVED, value.timeSource)
        assertEquals(observedAt, value.occurredAt)
        assertNull(value.merchant)
        assertNull(value.platform)
        assertNull(value.transactionId)
    }

    @Test fun `platform and merchant labels do not become a category or inferred platform`() {
        val value = parsed(notification("支付成功\n支付金额：88.00元\n商户名称：虚构淘宝服务商"))
        assertEquals("虚构淘宝服务商", value.merchant)
        assertNull(value.platform)
        assertEquals(RecordStatus.CONFIRMED, value.status)
    }

    @Test fun `split accessibility labels still resolve structured receipt`() {
        val value = parsed(page("支付成功\n实付金额\n¥\n23.50\n商户名称\n虚构青禾餐厅\n付款时间\n2026-10-16 12:30:09\n交易单号\nWXFICTION202610160002"))
        assertEquals(RecordStatus.CONFIRMED, value.status)
        assertEquals(2350L, value.amountMinor)
        assertEquals("虚构青禾餐厅", value.merchant)
        assertEquals("WXFICTION202610160002", value.transactionId)
    }

    @Test fun `multiple conflicting actual amounts require review`() {
        val value = parsed(notification("支付成功\n实付金额：23.50元\n实际支付：24.50元"))
        assertEquals(RecordStatus.REVIEW, value.status)
        assertNull(value.amountMinor)
        assertTrue(value.reviewReason!!.contains("多个不同实付金额"))
    }

    @Test fun `contradictory actual and payment amounts also require review`() {
        val value = parsed(notification("支付成功\n实付金额：23.50元\n付款金额：24.50元"))
        assertEquals(RecordStatus.REVIEW, value.status)
        assertNull(value.amountMinor)
    }

    @Test fun `order total and discount without actual amount cannot confirm spending`() {
        val value = parsed(notification("支付成功\n订单总价：30.00元\n优惠：6.50元"))
        assertNull(value.amountMinor)
        assertEquals(RecordStatus.REVIEW, value.status)
        assertTrue(value.reviewReason!!.contains("实际付款金额"))
    }

    @Test fun `a lone unlabelled number does not become a payment`() {
        val value = parsed(notification("支付成功\n23.50"))
        assertNull(value.amountMinor)
        assertEquals(RecordStatus.REVIEW, value.status)
    }

    @Test fun `single currency amount on successful trusted payment source is usable`() {
        val value = parsed(notification("支付成功\n¥23.50"))
        assertEquals(2350L, value.amountMinor)
        assertEquals(RecordStatus.CONFIRMED, value.status)
    }

    @Test fun `balance assets and reward value cannot become actual payment amounts`() {
        listOf(
            "账户余额：¥888.00", "账户余额\n¥888.00", "账户资产：¥888.00", "积分价值：¥888.00", "积分价值\n¥888.00",
            "可用金额\n¥888.00", "累计支出\n¥888.00", "参考价值：¥888.00",
        ).forEach { otherAmount ->
            listOf(notification("支付成功\n$otherAmount"), page("支付成功\n$otherAmount\n交易单号：WXFICTIONBALANCE0001")).forEach { input ->
                val value = parsed(input)
                assertNull("Not actual payment: $otherAmount", value.amountMinor)
                assertEquals(RecordStatus.REVIEW, value.status)
            }
        }
    }

    @Test fun `explicit payment amount remains usable alongside a balance field`() {
        val value = parsed(notification("支付成功\n实付金额：23.50元\n账户余额：¥888.00"))
        assertEquals(2350L, value.amountMinor)
        assertEquals(RecordStatus.CONFIRMED, value.status)
    }

    @Test fun `sign before currency is not discarded during amount fallback`() {
        listOf("+¥23.50", "-¥23.50", "¥+23.50", "¥-23.50", "−¥23.50").forEach { amount ->
            val value = parsed(notification("交易成功\n$amount"))
            assertEquals("Signed amount $amount needs explicit direction handling", RecordStatus.REVIEW, value.status)
            assertNull(value.amountMinor)
        }
    }

    @Test fun `alipay incoming transaction direction cannot become outgoing spending`() {
        listOf("交易类型：收入", "收支：收入", "收支类型\n收入", "资金方向：转入", "收入").forEach { direction ->
            val input = page("交易成功\n$direction\n+¥23.50\n交易单号：ALIFICTIONINCOME0001").copy(
                packageName = PaymentChannel.ALIPAY.packageName,
                screenClass = "com.alipay.mobile.bill.detail.ui.BillDetailActivity",
            )
            assertNull("Incoming flow must be discarded: $direction", PaymentParser.parse(input))
        }
    }

    @Test fun `successful inline payment amount is usable`() {
        val value = parsed(notification("您已成功支付23.50元\n商户：虚构便利店"))
        assertEquals(2350L, value.amountMinor)
        assertEquals(RecordStatus.CONFIRMED, value.status)
    }

    @Test fun `failed cancelled pending and negated success never confirm spending`() {
        listOf("支付失败", "付款已取消", "交易已关闭", "待支付", "支付处理中", "未支付成功", "付款未成功", "并不是支付成功", "尚未付款成功", "未能成功支付").forEach { state ->
            assertNull("Must discard fictional $state", PaymentParser.parse(notification("$state\n支付金额：23.50元")))
        }
    }

    @Test fun `hypothetical success statement does not prove payment succeeded`() {
        val value = parsed(notification("支付成功后可查看凭证\n实付金额：23.50元"))
        assertEquals(RecordStatus.REVIEW, value.status)
        assertTrue(value.reviewReason!!.contains("成功状态"))
    }

    @Test fun `conflicting success and failure states go to review`() {
        val value = parsed(notification("支付失败，请重试\n支付成功后可查看凭证\n交易成功\n实付金额：23.50元"))
        assertEquals(RecordStatus.REVIEW, value.status)
    }

    @Test fun `known source with ambiguous state enters review`() {
        val value = parsed(notification("交易状态：暂不可用\n实付金额：23.50元"))
        assertEquals(2350L, value.amountMinor)
        assertEquals(RecordStatus.REVIEW, value.status)
    }

    @Test fun `success without amount enters review`() {
        val value = parsed(notification("支付成功"))
        assertEquals(RecordStatus.REVIEW, value.status)
        assertNull(value.amountMinor)
    }

    @Test fun `explicit trusted success title and body amount can prove payment status`() {
        val value = parsed(notification("付款金额：23.50元", title = "支付成功"))
        assertEquals(RecordStatus.CONFIRMED, value.status)
        assertEquals(2350L, value.amountMinor)
    }

    @Test fun `payment page without id or time cannot identify a historical receipt`() {
        val value = parsed(page("支付成功\n实付金额：23.50元"))
        assertEquals(RecordStatus.REVIEW, value.status)
        assertTrue(value.reviewReason!!.contains("历史页面"))
    }

    @Test fun `historical page repeated with later observed time has stable fingerprints`() {
        val input = page("支付成功\n实付金额：23.50元\n交易单号：WXFICTION202610160003")
        val first = parsed(input)
        val reopened = parsed(input.copy(observedAt = observedAt + 86_400_000L))
        assertEquals(first.captureKey, reopened.captureKey)
        assertEquals(first.contentFingerprint, reopened.contentFingerprint)
    }

    @Test fun `equal amount independent transaction ids remain distinct`() {
        val first = parsed(notification("支付成功\n支付金额：23.50元\n交易单号：WXFICTION202610160004"))
        val second = parsed(notification("支付成功\n支付金额：23.50元\n交易单号：WXFICTION202610160005"))
        assertNotEquals(first.transactionId, second.transactionId)
        assertNotEquals(first.captureKey, second.captureKey)
        assertNotEquals(first.contentFingerprint, second.contentFingerprint)
    }

    @Test fun `equal independent notifications preserve source identity without pretending payment times`() {
        val first = parsed(notification("支付成功\n支付金额：23.50元", "fictional-notification:100"))
        val second = parsed(notification("支付成功\n支付金额：23.50元", "fictional-notification:101"))
        assertNotEquals(first.captureKey, second.captureKey)
        assertEquals(first.contentFingerprint, second.contentFingerprint)
        assertEquals(TimeSource.OBSERVED, second.timeSource)
    }

    @Test fun `merchant order id is never treated as channel transaction id or merchant`() {
        val value = parsed(notification("支付成功\n支付金额：23.50元\n商户订单号：SHOPFICTION202610160001\n订单号：ORDERFICTION0001"))
        assertNull(value.transactionId)
        assertNull(value.merchant)
    }

    @Test fun `missing labelled merchant does not swallow the next field label`() {
        val value = parsed(notification("支付成功\n商户名称\n实付金额\n23.50"))
        assertNull(value.merchant)
        assertEquals(2350L, value.amountMinor)
    }

    @Test fun `all nonexpense flows remain review and preserve their kind`() {
        val cases = listOf(
            "退款申请已提交\n退款金额：5.00元\n原交易支付成功" to EntryKind.REFUND,
            "退款到账\n退款金额：5.00元" to EntryKind.REFUND,
            "转账成功\n转账金额：23.50元" to EntryKind.TRANSFER,
            "充值成功\n充值金额：23.50元" to EntryKind.TOP_UP,
            "提现成功\n提现金额：23.50元" to EntryKind.WITHDRAWAL,
        )
        cases.forEach { (body, kind) ->
            val value = parsed(notification(body))
            assertEquals(kind, value.kind)
            assertEquals(RecordStatus.REVIEW, value.status)
            assertNotNull(value.reviewReason)
        }
    }

    @Test fun `receipt income is not outgoing expense`() {
        assertNull(PaymentParser.parse(notification("收款成功\n收款金额：23.50元")))
        assertNull(PaymentParser.parse(notification("收到虚构用户的转账\n支付金额：23.50元")))
    }

    @Test fun `original trade number is not a strong identity for separate partial refunds`() {
        val first = parsed(notification("退款到账\n退款金额：5.00元\n交易单号：WXFICTIONORIGINAL0001"))
        val second = parsed(notification("退款到账\n退款金额：3.00元\n交易单号：WXFICTIONORIGINAL0001"))
        assertNull(first.transactionId)
        assertNull(second.transactionId)
        assertNotEquals(first.contentFingerprint, second.contentFingerprint)
        assertEquals(RecordStatus.REVIEW, first.status)
        assertEquals(RecordStatus.REVIEW, second.status)
    }

    @Test fun `untrusted apps chat senders and general activities are discarded`() {
        val input = notification("支付成功\n支付金额：23.50元")
        assertNull(PaymentParser.parse(input.copy(packageName = "com.fictional.chat")))
        assertNull(PaymentParser.parse(input.copy(title = "虚构朋友")))
        assertNull(PaymentParser.parse(page(input.texts.single()).copy(screenClass = "com.tencent.mm.ui.LauncherUI")))
        assertNull(PaymentParser.parse(input.copy(source = CaptureSource.MANUAL)))
    }

    @Test fun `verification codes and unrelated notification text are discarded`() {
        assertNull(PaymentParser.parse(notification("支付成功\n支付金额：23.50元\n验证码：000000")))
        assertNull(PaymentParser.parse(notification("虚构周末活动提醒")))
    }

    @Test fun `unsafe negative fractional overflowing zero and foreign amounts cannot auto confirm`() {
        listOf("-23.50", "+23.50", "23.501", "92233720368547758.08", "0.00", "23.50美元").forEach { amount ->
            val value = parsed(notification("支付成功\n支付金额：$amount"))
            assertEquals("Unsafe amount $amount", RecordStatus.REVIEW, value.status)
        }
    }

    @Test fun `invalid and conflicting times do not masquerade as payment times`() {
        val invalid = parsed(notification("支付成功\n支付金额：23.50元\n付款时间：2026-02-30 12:30:00"))
        assertEquals(RecordStatus.REVIEW, invalid.status)
        assertEquals(TimeSource.OBSERVED, invalid.timeSource)
        val conflicting = parsed(notification("支付成功\n支付金额：23.50元\n付款时间：2026-10-16 12:30:00\n支付时间：2026-10-16 13:30:00"))
        assertEquals(RecordStatus.REVIEW, conflicting.status)
        assertEquals(TimeSource.OBSERVED, conflicting.timeSource)
    }

    @Test fun `nonexistent and ambiguous daylight saving times require review without adjusting time`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            listOf("2026-03-08 02:30:00", "2026-11-01 01:30:00").forEach { time ->
                val value = parsed(notification("支付成功\n支付金额：23.50元\n付款时间：$time"))
                assertEquals("Cannot invent an offset for $time", RecordStatus.REVIEW, value.status)
                assertEquals(TimeSource.OBSERVED, value.timeSource)
                assertEquals(observedAt, value.occurredAt)
                assertTrue(value.reviewReason!!.contains("付款时间"))
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun `hashes contain no merchant source key or original evidence`() {
        val value = parsed(notification("支付成功\n支付金额：23.50元\n商户：虚构隐私餐厅", key = "fictional-private-source-key"))
        assertTrue(Regex("[0-9a-f]{64}").matches(value.captureKey))
        assertTrue(Regex("[0-9a-f]{64}").matches(value.contentFingerprint))
        assertFalse(value.captureKey.contains("fictional"))
    }

    @Test fun `alipay is supported with an explicit trusted title and necessary window only`() {
        val base = notification("支付成功\n实际支付金额：23.50元\n支付宝交易号：ALIFICTION202610160001")
            .copy(packageName = PaymentChannel.ALIPAY.packageName, title = "支付宝")
        val value = parsed(base)
        assertEquals(RecordStatus.CONFIRMED, value.status)
        assertEquals(PaymentChannel.ALIPAY, value.channel)
        assertEquals(2350L, value.amountMinor)
        assertFalse(CapturePolicy.isPaymentWindow(PaymentChannel.ALIPAY, "com.alipay.mobile.quinox.LauncherActivity"))
        assertTrue(CapturePolicy.isPaymentWindow(PaymentChannel.ALIPAY, "com.alipay.android.msp.ui.views.MspContainerActivity"))
    }
}
