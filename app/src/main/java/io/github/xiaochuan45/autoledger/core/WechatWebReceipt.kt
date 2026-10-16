package io.github.xiaochuan45.autoledger.core

/** A shared browser is only a candidate, never authenticated payment evidence. */
object WechatWebReceipt {
    const val WINDOW = "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI"
    val labels = listOf("当前状态", "支付时间", "支付方式", "交易单号", "商户单号", "收单机构", "账单服务")
    const val REVIEW_REASON = "微信网页账单来源尚未验证，请核对后确认；本次不自动计入支出"

    fun isCandidate(channel: PaymentChannel, className: String?) =
        channel == PaymentChannel.WECHAT && className == WINDOW

    fun hasReceiptLabels(texts: List<String>): Boolean {
        if (texts.size > 160 || texts.sumOf { it.length.toLong() } > 16_384) return false
        val text = texts.joinToString("\n")
        return labels.all { label ->
            Regex("(?:^|\\n)[ \\t]*${Regex.escape(label)}(?:[ \\t]*[:：]|[ \\t]*\\n|[ \\t]+|$)")
                .containsMatchIn(text)
        }
    }

    /** Only the signed header BEFORE the status table; never a negative value in another field. */
    fun headerAmounts(text: String): List<String> {
        val status = Regex("(?m)^[ \\t]*当前状态(?:[ \\t]*[:：]|[ \\t]*$|[ \\t]+)").find(text) ?: return emptyList()
        val header = text.substring(0, status.range.first).lines().filter(String::isNotBlank)
        if (header.size > 8) return emptyList()
        return header.map(String::trim).filter { Regex("[-+][0-9]+\\.[0-9]{2}").matches(it) }
    }
}
