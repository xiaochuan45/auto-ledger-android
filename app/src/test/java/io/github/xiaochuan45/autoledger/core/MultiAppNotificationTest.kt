package io.github.xiaochuan45.autoledger.core

import org.junit.Assert.*
import org.junit.Test

class MultiAppNotificationTest {
    private fun input(app: CaptureApp = CaptureApp.PINDUODUO, body: String = "支付成功\n实付金额：42.70元\n支付方式：微信支付\n微信支付交易单号：FICTIONAL-WX-MULTI-001") =
        CaptureInput(CaptureSource.PLATFORM_NOTIFICATION, app.packages.first(), listOf(body),
            1_800_000_000_000L, "fictional-source-key", title = "支付成功")

    @Test fun completedProviderNoticesCanConfirmWhileNegatedOrConditionalStatementsCannot() {
        listOf("已支付￥42.70", "已付款42.70元", "已扣款42.70元").forEach { text ->
            val p = requireNotNull(PaymentParser.parse(CaptureInput(CaptureSource.NOTIFICATION,
                PaymentChannel.WECHAT.packageName, listOf(text), 1_800_000_000_000L, "fictional-completed", title = "微信支付")))
            assertEquals(4270L, p.amountMinor)
            assertEquals(RecordStatus.CONFIRMED, p.status)
        }
        listOf("不是已支付42.70元", "未能已付款42.70元", "如果已扣款42.70元请联系客服").forEach { text ->
            val p = PaymentParser.parse(CaptureInput(CaptureSource.NOTIFICATION,
                PaymentChannel.WECHAT.packageName, listOf(text), 1_800_000_000_000L, "fictional-rejected", title = "微信支付"))
            assertTrue(p == null || p.status == RecordStatus.REVIEW)
        }
    }

    @Test fun allFivePlatformsUseTheirOwnOriginAndNeverImplyAPaymentAccountOrCategory() {
        CaptureApp.platformApps.forEach { app ->
            val p = requireNotNull(PaymentParser.parse(input(app, "支付成功\n实付金额：42.70元\n备注：微信支付宝均可使用")))
            assertEquals(app.platform, p.platform)
            assertEquals(PaymentChannel.UNKNOWN, p.channel)
            assertEquals(RecordStatus.REVIEW, p.status)
            assertNull(p.transactionId)
            assertEquals(4270L, p.amountMinor)
        }
    }
    @Test fun meituanTakeoutIsAnExactAdditionalPackageNotAnArbitraryPrefix() {
        assertEquals(CaptureApp.MEITUAN, CaptureApp.fromPackage("com.sankuai.meituan.takeoutnew"))
        assertNull(CaptureApp.fromPackage("com.sankuai.meituan.fake"))
        assertNull(CaptureApp.fromPackage("fake.com.tencent.mm"))
        assertNull(PaymentChannel.fromPackage(""))
    }
    @Test fun platformProviderIdentifiersAreNamespacedAndOrdinaryOrderNumbersAreNotStrongIds() {
        val strong = requireNotNull(PaymentParser.parse(input()))
        assertEquals(PaymentChannel.WECHAT, strong.channel)
        assertEquals("FICTIONAL-WX-MULTI-001", strong.transactionId)
        assertEquals(RecordStatus.REVIEW, strong.status)
        val order = requireNotNull(PaymentParser.parse(input(body = "支付成功\n实付金额：42.70元\n支付方式：微信支付\n交易号：ORDER-NOT-WX-0001\n订单号：ORDER-NOT-WX-0001")))
        assertNull(order.transactionId)
        val alipay = requireNotNull(PaymentParser.parse(input(body = "支付成功\n实付金额：42.70元\n支付方式：支付宝\n支付宝交易号：FICTIONAL-ALI-001")))
        assertEquals(PaymentChannel.ALIPAY, alipay.channel)
        assertEquals("FICTIONAL-ALI-001", alipay.transactionId)
    }
    @Test fun conflictingOrVaguePaymentMethodsStayUnknown() {
        listOf("支付方式：微信支付\n支付渠道：支付宝", "支付方式：免密支付", "支付方式：微信支付或支付宝", "备注：支付方式微信").forEach {
            val p = requireNotNull(PaymentParser.parse(input(body = "支付成功\n实付金额：42.70元\n$it")))
            assertEquals(PaymentChannel.UNKNOWN, p.channel)
            assertEquals(RecordStatus.REVIEW, p.status)
        }
    }
    @Test fun sourceRouteTitleAndPackageAllHaveToMatch() {
        assertNull(PaymentParser.parse(input().copy(source = CaptureSource.NOTIFICATION)))
        assertNull(PaymentParser.parse(input().copy(packageName = "com.example.fictional")))
        assertNull(PaymentParser.parse(input().copy(title = "限时补贴")))
        assertNull(PaymentParser.parse(input().copy(title = "拼多多")))
        assertNull(PaymentParser.parse(input(CaptureApp.WECHAT)))
    }
    @Test fun failedCancelledShippingAndPromotionsCannotBecomeExpenses() {
        listOf("支付失败\n实付金额：42.70元", "订单已取消\n实付金额：42.70元", "商品已发货，价值42.70元", "下单优惠42.70元，等待付款").forEach {
            assertNull(PaymentParser.parse(input(body = it).copy(title = "交易提醒")))
        }
    }
    @Test fun actualAmountWinsOverOriginalPriceAndAmbiguityIsNotGuessed() {
        val p = requireNotNull(PaymentParser.parse(input(body = "支付成功\n订单金额：50.00元\n优惠金额：7.30元\n实付金额：42.70元")))
        assertEquals(4270L, p.amountMinor)
        val conflict = requireNotNull(PaymentParser.parse(input(body = "支付成功\n实付金额：42.70元\n支付金额：40.00元")))
        assertNull(conflict.amountMinor)
        assertEquals(RecordStatus.REVIEW, conflict.status)
    }
    @Test fun identicalEventKeysAcrossDifferentAppsCannotCollapseTheirReceipts() {
        val a = requireNotNull(PaymentParser.parse(input(CaptureApp.TAOBAO)))
        val b = requireNotNull(PaymentParser.parse(input(CaptureApp.JINGDONG)))
        assertNotEquals(a.captureKey, b.captureKey)
    }
    @Test fun sameAmountDistinctPlatformOrdersRemainDifferentEvenIfSystemEventMetadataCollides() {
        val a = requireNotNull(PaymentParser.parse(input(body = "支付成功\n实付金额：42.70元\n订单号：FICTIONAL-ORDER-A")))
        val b = requireNotNull(PaymentParser.parse(input(body = "支付成功\n实付金额：42.70元\n订单号：FICTIONAL-ORDER-B")))
        assertNotEquals(a.captureKey, b.captureKey)
        assertNull(a.transactionId); assertNull(b.transactionId)
    }
    @Test fun platformConflictLeavesPlatformUnknownRatherThanOverridingDeclaredEvidence() {
        val p = requireNotNull(PaymentParser.parse(input(body = "支付成功\n实付金额：42.70元\n消费平台：美团")))
        assertNull(p.platform)
        assertTrue(p.reviewReason!!.contains("来源"))
    }
    @Test fun refundApplicationsAndTransfersRemainReviewAndDoNotReuseOriginalPaymentIdentity() {
        val p = requireNotNull(PaymentParser.parse(input(body = "申请退款\n退款金额：12.00元\n支付方式：微信支付\n微信支付交易单号：ORIGINAL-FICTIONAL-001")))
        assertEquals(EntryKind.REFUND, p.kind)
        assertEquals(RecordStatus.REVIEW, p.status)
        assertNull(p.transactionId)
    }
}
