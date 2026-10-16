package io.github.xiaochuan45.autoledger.core

import org.junit.Assert.*
import org.junit.Test

/** Fictional layout and values only; never copied from a user's bill. */
class WechatWebReceiptTest {
    private fun merchantMini() = input(listOf("虚构商户", "喜欢", "虚构店铺介绍", "服务", "会员", "交易详情",
        "虚构栏目一", "虚构栏目二", "虚构栏目三", "-42.70", "虚构商户", "当前状态支付成功",
        "支付时间2025年3月12日 10:20:30", "支付方式零钱", "交易单号FICTIONAL-MINI-A"))
        .copy(source = CaptureSource.USER_WEB_RECEIPT, screenClass = WechatWebReceipt.MINI_WINDOW)

    @Test fun selectedMerchantMiniProfileHasExactAmountButNeverBecomesAnAutomaticEntry() {
        val value = requireNotNull(PaymentParser.parse(merchantMini()))
        assertEquals(4270L, value.amountMinor)
        assertEquals(RecordStatus.REVIEW, value.status)
        assertEquals(CaptureSource.WEB_RECEIPT, value.source)
        assertEquals("FICTIONAL-MINI-A", value.transactionId)
        assertEquals(TimeSource.PAYMENT, value.timeSource)
        assertNull(value.merchant)
        assertNull(value.platform)
        assertNull(PaymentParser.parse(merchantMini().copy(source = CaptureSource.ACCESSIBILITY)))
        assertNull(PaymentParser.parse(merchantMini().copy(screenClass = "com.tencent.mm.ui.LauncherUI")))
        assertNull(PaymentParser.parse(merchantMini().copy(packageName = PaymentChannel.ALIPAY.packageName)))
    }

    @Test fun selectedMiniStillNeedsEveryColumnAndDoesNotTurnIncomeOrRemarkNumbersIntoExpense() {
        val mini = merchantMini()
        WechatWebReceipt.requiredLabels.forEach { label ->
            assertNull(PaymentParser.parse(mini.copy(texts = mini.texts.filterNot { it.startsWith(label) })))
        }
        assertNull(PaymentParser.parse(mini.copy(texts = mini.texts.map { if (it == "-42.70") "+42.70" else it })))
        val remarkOnly = requireNotNull(PaymentParser.parse(mini.copy(texts = mini.texts.filterNot { it == "-42.70" } + "备注：-42.70")))
        assertNull(remarkOnly.amountMinor)
        val multiple = requireNotNull(PaymentParser.parse(mini.copy(texts = listOf("-51.20") + mini.texts)))
        assertNull(multiple.amountMinor)
        assertEquals(RecordStatus.REVIEW, multiple.status)
    }

    @Test fun longMerchantHeaderExceptionDoesNotRelaxOrdinaryWebHeaderRules() {
        val ordinary = merchantMini().copy(screenClass = WechatWebReceipt.WINDOW)
        assertNull(requireNotNull(PaymentParser.parse(ordinary)).amountMinor)
    }
    private fun receipt(amount: String = "-42.70", state: String = "支付成功", id: String = "FICTIONAL-WEB-TRADE-A") =
        listOf("虚构青禾商店", amount, "当前状态", state, "支付时间", "2025年3月12日 10:20:30",
            "支付方式", "零钱", "交易单号", id, "商户单号", "FICTIONAL-MERCHANT-ORDER-A",
            "收单机构", "虚构支付机构", "账单服务")

    private fun input(texts: List<String> = receipt()) = CaptureInput(CaptureSource.ACCESSIBILITY,
        PaymentChannel.WECHAT.packageName, texts, 1_800_000_000_000L, "fictional-web-window",
        screenClass = WechatWebReceipt.WINDOW)

    @Test fun completeWebReceiptIsAlwaysReviewAndKeepsPaymentTimeAndStrongId() {
        val value = requireNotNull(PaymentParser.parse(input()))
        assertEquals(4270L, value.amountMinor)
        assertEquals(RecordStatus.REVIEW, value.status)
        assertEquals(CaptureSource.WEB_RECEIPT, value.source)
        assertEquals(TimeSource.PAYMENT, value.timeSource)
        assertEquals("FICTIONAL-WEB-TRADE-A", value.transactionId)
        assertNull(value.merchant) // An unlabeled heading is not an authenticated merchant field.
        assertNull(value.platform)
        assertTrue(value.reviewReason!!.contains(WechatWebReceipt.REVIEW_REASON))
    }

    @Test fun ordinaryWebArticleOrMissingColumnCannotPassScopeEvenWithPaymentWords() {
        assertNull(PaymentParser.parse(input(listOf("支付成功", "实付金额：42.70元"))))
        WechatWebReceipt.requiredLabels.forEach { missing ->
            assertNull("Missing $missing", PaymentParser.parse(input(receipt().filterNot { it == missing })))
        }
        val article = WechatWebReceipt.labels.map { "本文介绍${it}的含义" } + listOf("支付成功", "实付金额：42.70元")
        assertNull(PaymentParser.parse(input(article)))
    }

    @Test fun sharedContainerOnOtherChannelAndGenericBrowserAreRejected() {
        assertNull(PaymentParser.parse(input().copy(packageName = PaymentChannel.ALIPAY.packageName)))
        assertNull(PaymentParser.parse(input().copy(screenClass = "com.tencent.mm.ui.LauncherUI")))
        assertNull(PaymentParser.parse(input().copy(packageName = "com.example.fictional.browser")))
    }

    @Test fun pastedPerfectReceiptStillCannotBecomeConfirmedExpense() {
        // Labels are classification evidence, not proof that the web origin is genuine.
        val value = requireNotNull(PaymentParser.parse(input(receipt() + "商户名称：虚构可伪造标题")))
        assertEquals(RecordStatus.REVIEW, value.status)
        assertTrue(value.reviewReason!!.contains("来源尚未验证"))
    }

    @Test fun incomeOrNegativeAmountInAnUnrelatedFieldIsNeverAnExpenseAmount() {
        assertNull(PaymentParser.parse(input(receipt("+42.70"))))
        val fieldOnly = requireNotNull(PaymentParser.parse(input(receipt("未知金额") + listOf("备注", "-42.70"))))
        assertNull(fieldOnly.amountMinor)
        assertEquals(RecordStatus.REVIEW, fieldOnly.status)
    }

    @Test fun failedPendingOrConflictingAmountsCannotBecomeConfirmed() {
        listOf("支付失败", "待支付", "付款处理中").forEach { state ->
            assertNull(PaymentParser.parse(input(receipt(state = state))))
        }
        val ambiguous = requireNotNull(PaymentParser.parse(input(receipt() + "实付金额：43.70元")))
        assertNull(ambiguous.amountMinor)
        assertEquals(RecordStatus.REVIEW, ambiguous.status)
        val mixed = requireNotNull(PaymentParser.parse(input(receipt() + "支付失败")))
        assertEquals(RecordStatus.REVIEW, mixed.status)
        assertTrue(mixed.reviewReason!!.contains("失败状态"))
        val unknown = requireNotNull(PaymentParser.parse(input(receipt(state = "状态未知"))))
        assertEquals(RecordStatus.REVIEW, unknown.status)
        assertNull(unknown.amountMinor)
        assertTrue(unknown.reviewReason!!.contains("未读到明确的付款成功状态"))
    }

    @Test fun currencyAuthorizationAndMalformedEvidenceFailClosed() {
        assertNull(PaymentParser.parse(input(receipt() + "请输入支付密码")))
        assertNull(requireNotNull(PaymentParser.parse(input(receipt() + "币种：USD"))).amountMinor)
        assertNull(requireNotNull(PaymentParser.parse(input(receipt("-42.701")))).amountMinor)
        assertNull(PaymentParser.parse(input(receipt() + "x".repeat(16_384))))
    }

    @Test fun mergedRowsAndMissingNonFinancialFooterStillKeepExactPaymentFields() {
        val rows = listOf("虚构商店", "-42.70", "当前状态支付成功", "支付时间2025年3月12日 10:20:30",
            "支付方式零钱", "交易单号FICTIONAL-MERGED-A")
        val value = requireNotNull(PaymentParser.parse(input(rows)))
        assertEquals(4270L, value.amountMinor)
        assertEquals("FICTIONAL-MERGED-A", value.transactionId)
        assertEquals(TimeSource.PAYMENT, value.timeSource)
        assertEquals(RecordStatus.REVIEW, value.status)
        assertEquals(value, PaymentParser.parse(input(listOf(rows.joinToString("\n")))))
    }

    @Test fun userRequestedPartialReceiptCannotBypassChannelOrConfirmationRules() {
        val partial = input(listOf("虚构商店", "-42.70", "当前状态支付成功", "支付时间2025年3月12日 10:20:30"))
        assertNull(PaymentParser.parse(partial))
        val requested = partial.copy(source = CaptureSource.USER_WEB_RECEIPT)
        val value = requireNotNull(PaymentParser.parse(requested))
        assertEquals(4270L, value.amountMinor)
        assertEquals(RecordStatus.REVIEW, value.status)
        assertNull(value.transactionId)
        assertNull(PaymentParser.parse(requested.copy(screenClass = "com.tencent.mm.ui.LauncherUI")))
        assertNull(PaymentParser.parse(requested.copy(packageName = PaymentChannel.ALIPAY.packageName)))
        assertNull(PaymentParser.parse(requested.copy(texts = listOf("虚构网页", "支付成功"))))
    }

    @Test fun boundedPrefixComparisonNeverCopiesValuesOrReadsBeyondTheFirstValueCharacter() {
        val merged = object : CharSequence {
            override val length = 8
            override fun get(index: Int): Char {
                check(index <= 4) { "Do not inspect the field value" }
                return "当前状态支付成功"[index]
            }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("No copying")
            override fun toString(): String = error("No copying")
        }
        assertTrue(WechatWebReceipt.hasLabelPrefix(merged, "当前状态"))
        assertFalse(WechatWebReceipt.hasLabelPrefix("支付时间是什么", "支付时间"))
        assertFalse(WechatWebReceipt.hasLabelPrefix("交易单号格式介绍", "交易单号"))
        val huge = object : CharSequence {
            override val length = 100_000
            override fun get(index: Int): Char = error("No body reads")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("No copying")
            override fun toString(): String = error("No copying")
        }
        assertFalse(WechatWebReceipt.hasLabelPrefix(huge, "当前状态"))
    }

    @Test fun historicalReopenKeepsFingerprintButDifferentTradeNumberDoesNot() {
        val a = requireNotNull(PaymentParser.parse(input()))
        val replay = requireNotNull(PaymentParser.parse(input().copy(observedAt = 1_800_086_400_000L, sourceKey = "fictional-reopen")))
        val b = requireNotNull(PaymentParser.parse(input(receipt(id = "FICTIONAL-WEB-TRADE-B"))))
        assertEquals(a.contentFingerprint, replay.contentFingerprint)
        assertEquals(a.occurredAt, replay.occurredAt)
        assertNotEquals(a.contentFingerprint, b.contentFingerprint)
        assertEquals(RecordStatus.REVIEW, replay.status)
    }
}
