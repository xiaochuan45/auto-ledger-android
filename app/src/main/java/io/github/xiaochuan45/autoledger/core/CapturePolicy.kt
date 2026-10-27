package io.github.xiaochuan45.autoledger.core

/** Narrow native allowlists plus a separately scoped, review-only WeChat browser route. */
object CapturePolicy {
    private val paymentWindows = mapOf(
        PaymentChannel.WECHAT to setOf(
            "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI",
            "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI",
            "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoUI",
        ),
        PaymentChannel.ALIPAY to setOf(
            "com.alipay.android.msp.ui.views.MspContainerActivity",
            "com.alipay.mobile.bill.detail.ui.BillDetailActivity",
            "com.alipay.mobile.trade.detail.ui.TradeInfoActivity",
        ),
    )

    fun isTrustedNotificationTitle(channel: PaymentChannel, title: String?): Boolean =
        CaptureApp.entries.firstOrNull { it.paymentChannel == channel }?.let { NotificationAdapters.forApp(it).matches(title) } == true

    fun isPaymentWindow(channel: PaymentChannel, className: String?): Boolean =
        className?.let { it in paymentWindows[channel].orEmpty() } == true

    fun accepts(input: CaptureInput): Boolean {
        if (input.sourceKey.isBlank() || input.texts.isEmpty()) return false
        if (input.source == CaptureSource.PLATFORM_NOTIFICATION) return NotificationAdapters.admit(input) != null
        val channel = PaymentChannel.fromPackage(input.packageName) ?: return false
        return when (input.source) {
            CaptureSource.NOTIFICATION -> isTrustedNotificationTitle(channel, input.title)
            CaptureSource.ACCESSIBILITY -> isPaymentWindow(channel, input.screenClass) ||
                (WechatWebReceipt.isCandidate(channel, input.screenClass) && WechatWebReceipt.hasReceiptLabels(input.texts))
            CaptureSource.USER_WEB_RECEIPT -> WechatWebReceipt.isExplicitCandidate(channel, input.screenClass) &&
                WechatWebReceipt.hasReceiptLabels(input.texts,
                    userRequested = !WechatWebReceipt.isMiniCandidate(channel, input.screenClass))
            CaptureSource.MANUAL, CaptureSource.WEB_RECEIPT, CaptureSource.PLATFORM_NOTIFICATION -> false // Only the scoped parser can produce a web receipt.
        }
    }
}
