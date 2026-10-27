package io.github.xiaochuan45.autoledger.core

enum class CaptureSource { NOTIFICATION, ACCESSIBILITY, MANUAL, WEB_RECEIPT, USER_WEB_RECEIPT, PLATFORM_NOTIFICATION }
enum class PaymentChannel(val label: String, val packageName: String) {
    WECHAT("微信", "com.tencent.mm"),
    ALIPAY("支付宝", "com.eg.android.AlipayGphone"),
    UNKNOWN("未知", "");

    companion object {
        val captureChannels = listOf(WECHAT, ALIPAY)
        fun fromPackage(packageName: String): PaymentChannel? = entries.firstOrNull { it.packageName.isNotEmpty() && it.packageName == packageName }
    }
}
enum class EntryKind { EXPENSE, REFUND, TRANSFER, TOP_UP, WITHDRAWAL }
enum class RecordStatus { CONFIRMED, REVIEW }
enum class TimeSource { PAYMENT, OBSERVED, USER }

/** Transient input only: never persist these text fragments or write them to logs. */
data class CaptureInput(
    val source: CaptureSource,
    val packageName: String,
    val texts: List<String>,
    val observedAt: Long,
    val sourceKey: String,
    val title: String? = null,
    val screenClass: String? = null,
)

/** Only minimal structured fields and irreversible evidence hashes may leave the parser. */
data class ParsedPayment(
    val amountMinor: Long?,
    val channel: PaymentChannel,
    val merchant: String?,
    val platform: String?,
    val occurredAt: Long,
    val timeSource: TimeSource,
    val kind: EntryKind,
    val status: RecordStatus,
    val transactionId: String?,
    val captureKey: String,
    val contentFingerprint: String,
    val source: CaptureSource,
    val reviewReason: String? = null,
)

data class LedgerDraft(
    val amountMinor: Long,
    val occurredAt: Long,
    val merchant: String? = null,
    val platform: String? = null,
    val channel: PaymentChannel = PaymentChannel.WECHAT,
    val kind: EntryKind = EntryKind.EXPENSE,
    val category: String = "未分类",
    val note: String = "",
)

object LedgerCategories {
    val defaults = listOf("餐饮", "交通", "购物", "娱乐", "生活缴费", "学习", "医疗", "其他", "未分类")
}
