package io.github.xiaochuan45.autoledger.capture

import io.github.xiaochuan45.autoledger.core.WechatWebReceipt

enum class WebReceiptRejection(val label: String) {
    MATCHED("必要栏目已找到"), FOREIGN_ROOT("当前窗口与微信不一致"),
    NATIVE_LIMIT("原生窗口结构超过检查上限"), WEB_LIMIT("网页结构超过检查上限"),
    NODE_UNAVAILABLE("系统节点缺失，结构不完整"), NO_WEBVIEW("没有可见的受支持网页节点"),
    MULTIPLE_WEBVIEWS("存在多个网页，无法确定账单区域"), NO_TEXT("网页没有提供可见文字或描述"),
    MISSING_LABELS("可读文字存在，但必要栏目未齐全"),
    FILTERED_NODES("网页节点被窗口、可见性或安全边界过滤"),
}

/** Fixed reasons and integer/bit metadata only; never takes raw text or field values. */
data class WebReceiptInspection(
    val reason: WebReceiptRejection,
    val nativeNodes: Int = 0,
    val webNodes: Int = 0,
    val textNodes: Int = 0,
    val longTextNodes: Int = 0,
    val nullPackageNodes: Int = 0,
    val labelMask: Int = 0,
    val foreignNodes: Int = 0,
    val otherWindowNodes: Int = 0,
    val invisibleNodes: Int = 0,
    val protectedNodes: Int = 0,
) {
    fun summary(): String = buildString {
        appendLine("最近网页检查：${reason.label}")
        appendLine("原生节点 $nativeNodes；网页节点 $webNodes；有文字节点 $textNodes；长文字节点 $longTextNodes；缺包名节点 $nullPackageNodes")
        appendLine("过滤：异包 $foreignNodes；窗口不符 $otherWindowNodes；不可见 $invisibleNodes；敏感控件 $protectedNodes")
        append("必要栏目：")
        append(WechatWebReceipt.requiredLabels.mapIndexed { index, label ->
            "$label=${if (labelMask and (1 shl index) != 0) "有" else "未见"}"
        }.joinToString("；"))
    }
}
