package io.github.xiaochuan45.autoledger.core

/** Where evidence came from is independent of which payment account funded it. Exact packages only. */
enum class CaptureApp(val label: String, val packages: Set<String>, val paymentChannel: PaymentChannel? = null,
    val platform: String? = null) {
    WECHAT("微信", setOf("com.tencent.mm"), PaymentChannel.WECHAT),
    ALIPAY("支付宝", setOf("com.eg.android.AlipayGphone"), PaymentChannel.ALIPAY),
    TAOBAO("淘宝", setOf("com.taobao.taobao"), platform = "淘宝"),
    JINGDONG("京东", setOf("com.jingdong.app.mall"), platform = "京东"),
    PINDUODUO("拼多多", setOf("com.xunmeng.pinduoduo"), platform = "拼多多"),
    MEITUAN("美团（含外卖）", setOf("com.sankuai.meituan", "com.sankuai.meituan.takeoutnew"), platform = "美团"),
    DOUYIN("抖音", setOf("com.ss.android.ugc.aweme"), platform = "抖音");

    val supplementary: Boolean get() = paymentChannel == null
    companion object {
        val platformApps: List<CaptureApp> = entries.filter { it.supplementary }
        fun fromPackage(value: String): CaptureApp? = entries.firstOrNull { value in it.packages }
    }
}

/** Bundled data, not executable rule scripts. Package + source + exact title must all agree. */
data class NotificationAdapter(val id: String, val app: CaptureApp, val titles: Set<String>, val version: Int = 1) {
    val source: CaptureSource get() = if (app.supplementary) CaptureSource.PLATFORM_NOTIFICATION else CaptureSource.NOTIFICATION
    fun matches(title: String?): Boolean = title?.trim() in titles
}

object NotificationAdapters {
    const val REVIEW_REASON = "平台交易通知仅作辅助线索，请核对实际付款及是否已由支付渠道记录"
    private val platformTitles = setOf("支付成功", "付款成功", "订单支付成功", "扣款成功", "自动扣款成功",
        "交易提醒", "交易通知", "支付通知", "退款成功", "退款到账")
    private val adapters = CaptureApp.entries.associateWith { app ->
        NotificationAdapter("${app.name.lowercase()}-notification", app, when (app) {
            CaptureApp.WECHAT -> setOf("微信支付", "支付成功", "付款成功")
            CaptureApp.ALIPAY -> setOf("支付宝", "支付宝支付", "支付助手", "交易提醒", "支付成功", "付款成功", "扣款成功", "自动扣款成功")
            else -> platformTitles
        })
    }
    fun forApp(app: CaptureApp): NotificationAdapter = adapters.getValue(app)
    fun admit(input: CaptureInput): NotificationAdapter? = CaptureApp.fromPackage(input.packageName)?.let(::forApp)
        ?.takeIf { it.source == input.source && it.matches(input.title) }
}
