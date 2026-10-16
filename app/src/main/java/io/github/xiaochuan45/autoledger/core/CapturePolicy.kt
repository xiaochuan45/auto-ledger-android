package io.github.xiaochuan45.autoledger.core

/** Narrow, version-dependent allowlists. General chat/web/launcher activities are not admitted. */
object CapturePolicy {
    private val notificationTitles = mapOf(
        PaymentChannel.WECHAT to setOf("微信支付", "支付成功", "付款成功"),
        PaymentChannel.ALIPAY to setOf(
            "支付宝", "支付宝支付", "支付助手", "交易提醒", "支付成功", "付款成功", "扣款成功", "自动扣款成功",
        ),
    )

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
        title?.trim()?.let { it in notificationTitles.getValue(channel) } == true

    fun isPaymentWindow(channel: PaymentChannel, className: String?): Boolean =
        className?.let { it in paymentWindows.getValue(channel) } == true

    fun accepts(input: CaptureInput): Boolean {
        val channel = PaymentChannel.fromPackage(input.packageName) ?: return false
        if (input.sourceKey.isBlank() || input.texts.isEmpty()) return false
        return when (input.source) {
            CaptureSource.NOTIFICATION -> isTrustedNotificationTitle(channel, input.title)
            CaptureSource.ACCESSIBILITY -> isPaymentWindow(channel, input.screenClass) ||
                (WechatWebReceipt.isCandidate(channel, input.screenClass) && WechatWebReceipt.hasReceiptLabels(input.texts))
            CaptureSource.MANUAL, CaptureSource.WEB_RECEIPT -> false // Only the scoped parser can produce a web receipt.
        }
    }
}
