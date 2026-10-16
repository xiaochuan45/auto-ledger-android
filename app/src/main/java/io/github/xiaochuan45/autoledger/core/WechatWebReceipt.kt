package io.github.xiaochuan45.autoledger.core

/** A shared browser is only a candidate, never authenticated payment evidence. */
object WechatWebReceipt {
    const val WINDOW = "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI"
    const val MINI_WINDOW = "com.tencent.mm.plugin.appbrand.ui.AppBrandPluginUI"
    val requiredLabels = listOf("当前状态", "支付时间", "支付方式", "交易单号")
    val labels = requiredLabels + listOf("商户单号", "收单机构", "账单服务")

    /** Compares only a bounded field-name prefix, never copies or retains its value. */
    fun hasLabelPrefix(value: CharSequence?, label: String): Boolean {
        if (value == null || value.length !in label.length..160) return false
        var first = 0
        while (first < minOf(4, value.length) && value[first].isWhitespace()) first++
        if (value.length - first < label.length || !label.indices.all { value[first + it] == label[it] }) return false
        val next = first + label.length
        if (next == value.length) return true
        val c = value[next]
        if (c.isWhitespace() || c == ':' || c == '：') return true
        return when (label) {
            "支付时间" -> c in '0'..'9'
            "交易单号", "商户单号" -> c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z'
            "当前状态" -> c in "支付交易扣未已待失取关退成处"
            "支付方式" -> c in "零微银储信现数亲支其余"
            else -> false
        }
    }

    fun normalizeRow(value: String): String {
        val label = labels.firstOrNull { hasLabelPrefix(value, it) } ?: return value
        val trimmed = value.trimStart()
        val suffix = trimmed.substring(label.length).trimStart { it.isWhitespace() || it == ':' || it == '：' }
        return if (suffix.isEmpty()) label else "$label\n$suffix"
    }
    const val REVIEW_REASON = "微信详情来源尚未验证，请核对后确认；本次不自动计入支出"

    fun isCandidate(channel: PaymentChannel, className: String?) =
        channel == PaymentChannel.WECHAT && className == WINDOW

    fun isExplicitCandidate(channel: PaymentChannel, className: String?) =
        channel == PaymentChannel.WECHAT && className in setOf(WINDOW, MINI_WINDOW)

    fun isMiniCandidate(channel: PaymentChannel, className: String?) =
        channel == PaymentChannel.WECHAT && className == MINI_WINDOW

    fun normalizeRows(value: String): String = value.lines().joinToString("\n", transform = ::normalizeRow)

    fun hasReceiptLabels(texts: List<String>, userRequested: Boolean = false): Boolean {
        if (texts.size > 160 || texts.sumOf { it.length.toLong() } > 16_384) return false
        val text = texts.joinToString("\n", transform = ::normalizeRows)
        val present = requiredLabels.filter { label ->
            Regex("(?:^|\\n)[ \\t]*${Regex.escape(label)}(?:[ \\t]*[:：]|[ \\t]*\\n|[ \\t]+|$)")
                .containsMatchIn(text)
        }
        return if (userRequested) "当前状态" in present && ("支付时间" in present || "交易单号" in present)
            else present.size == requiredLabels.size
    }

    /** Only the signed header BEFORE the status table; never a negative value in another field. */
    fun headerAmounts(text: String, merchantPage: Boolean = false): List<String> {
        val status = Regex("(?m)^[ \\t]*当前状态(?:[ \\t]*[:：]|[ \\t]*$|[ \\t]+)").find(text) ?: return emptyList()
        val header = text.substring(0, status.range.first).lines().filter(String::isNotBlank)
        // A selected merchant detail has profile/navigation rows above its transaction tab.
        // Still require a unique signed amount BEFORE the status table, never an amount in a later field.
        if (!merchantPage && header.size > 8) return emptyList()
        return header.map(String::trim).filter { Regex("[-+][0-9]+\\.[0-9]{2}").matches(it) }
    }
}
