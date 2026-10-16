package io.github.xiaochuan45.autoledger.capture

import io.github.xiaochuan45.autoledger.core.CaptureSource
import io.github.xiaochuan45.autoledger.core.PaymentChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class CaptureFailure {
    NOTIFICATION_READ, ACCESSIBILITY_READ, PARSE_OR_STORAGE, SYSTEM_SETTINGS,
    SKIPPED_INCOMPLETE, QUEUE_FULL,
    NOTIFICATION_REBIND,
}

enum class CaptureSkip(val label: String) {
    NOTIFICATION_GROUP("汇总通知"), NOTIFICATION_CONVERSATION("会话形式通知，未读取正文"),
    NOTIFICATION_METADATA("通知元数据缺失"), NOTIFICATION_TITLE("通知类型尚不支持"),
    EMPTY_TEXT("支持页面或通知没有可读文字"), SENSITIVE_GUARD("授权或敏感信息保护"),
    WINDOW_UNSUPPORTED("页面类型尚不支持，未读取页面"), WINDOW_UNVERIFIED("没有已验证的付款窗口"),
    ROOT_UNAVAILABLE("系统未提供当前付款窗口"), WINDOW_CHANGED("当前窗口已变化"),
    WINDOW_ID_MISSING("系统窗口标识不可用"),
    PARSER_REJECTED("已读取输入，但没有可识别的交易证据"),
    COMPATIBILITY_ONLY("只读兼容性检查中，未读取付款内容或入账"),
}

/** Process-local diagnostics only. No source text, title, merchant, amount or exception is retained. */
data class CaptureDiagnosticSnapshot(
    val observedCount: Long = 0,
    val parsedCount: Long = 0,
    val failureCount: Long = 0,
    val lastChannel: PaymentChannel? = null,
    val lastSource: CaptureSource? = null,
    val lastObservedAt: Long? = null,
    val lastFailure: CaptureFailure? = null,
    val notificationReconnectRequests: Long = 0,
    val lastNotificationReconnectAt: Long? = null,
    val notificationEvents: Long = 0,
    val windowEvents: Long = 0,
    val supportedWindowEvents: Long = 0,
    val skippedCount: Long = 0,
    val lastSkip: CaptureSkip? = null,
    val lastSkipSource: CaptureSource? = null,
    val skipCounts: Map<CaptureSkip, Long> = emptyMap(),
)

object CaptureDiagnostics {
    private var current = CaptureDiagnosticSnapshot()
    private val compatibility = CompatibilityCheck { android.os.SystemClock.elapsedRealtime() }

    fun startCompatibilityCheck() = compatibility.start()
    fun stopCompatibilityCheck() = compatibility.stop()
    fun clearCompatibilityCheck() = compatibility.clear()
    fun compatibilitySnapshot(): CompatibilitySnapshot = compatibility.snapshot()
    fun compatibilityActive(): Boolean = compatibility.snapshot().active

    @Synchronized fun compatibilityReport(appVersion: String, sdk: Int): String {
        val probe = compatibility.snapshot()
        return buildString {
            appendLine("本地账本 $appVersion / Android API $sdk")
            appendLine("兼容性检查：${if (probe.active) "进行中，暂停自动记录" else "未运行"}")
            appendLine("已选渠道窗口事件：${current.windowEvents}；支持窗口：${current.supportedWindowEvents}")
            appendLine("已选渠道通知事件：${current.notificationEvents}")
            appendLine("进入解析：${current.observedCount}；完成处理：${current.parsedCount}")
            appendLine("筛选跳过：${current.skippedCount}；异常：${current.failureCount}")
            appendLine("最近跳过：${current.lastSkip?.label ?: "无"}；来源：${current.lastSkipSource?.name ?: "无"}")
            appendLine("最近异常：${current.lastFailure?.name ?: "无"}")
            current.skipCounts.forEach { (reason, count) -> appendLine("跳过 ${reason.label}：$count") }
            appendLine("检查期间窗口类型（仅程序标识）：")
            probe.windows.forEach { appendLine("${it.channel.label}: ${it.className}") }
            if (probe.windows.isEmpty()) appendLine("无")
        }
    }

    @Synchronized internal fun notificationEvent() {
        current = current.copy(notificationEvents = current.notificationEvents.incrementSafely())
    }

    @Synchronized internal fun windowEvent(channel: PaymentChannel, className: String?, supported: Boolean, recordType: Boolean = true) {
        if (recordType) compatibility.window(channel, className)
        current = current.copy(windowEvents = current.windowEvents.incrementSafely(),
            supportedWindowEvents = if (supported) current.supportedWindowEvents.incrementSafely() else current.supportedWindowEvents)
    }

    @Synchronized internal fun skipped(reason: CaptureSkip, source: CaptureSource) {
        current = current.copy(skippedCount = current.skippedCount.incrementSafely(),
            lastSkip = reason, lastSkipSource = source,
            skipCounts = current.skipCounts + (reason to (current.skipCounts[reason] ?: 0L).incrementSafely()))
    }

    @Synchronized fun snapshot(): CaptureDiagnosticSnapshot = current

    @Synchronized internal fun observed(channel: PaymentChannel, source: CaptureSource, at: Long) {
        current = current.copy(
            observedCount = current.observedCount.incrementSafely(),
            lastChannel = channel,
            lastSource = source,
            lastObservedAt = at,
        )
    }

    @Synchronized internal fun parsed() {
        current = current.copy(parsedCount = current.parsedCount.incrementSafely())
    }

    @Synchronized internal fun failed(reason: CaptureFailure) {
        current = current.copy(failureCount = current.failureCount.incrementSafely(), lastFailure = reason)
    }

    @Synchronized internal fun notificationReconnectRequested(at: Long) {
        current = current.copy(
            notificationReconnectRequests = current.notificationReconnectRequests.incrementSafely(),
            lastNotificationReconnectAt = at,
        )
    }

    private fun Long.incrementSafely(): Long = if (this == Long.MAX_VALUE) this else this + 1
}

/** Binding is an observation in this process, not a promise that Android will keep it running. */
data class CaptureConnectionSnapshot(
    val notificationConnected: Boolean = false,
    val accessibilityConnected: Boolean = false,
)

internal object CaptureServiceConnections {
    private val mutableState = MutableStateFlow(CaptureConnectionSnapshot())
    val changes = mutableState.asStateFlow()

    var notificationConnected: Boolean
        get() = mutableState.value.notificationConnected
        set(value) { mutableState.update { it.copy(notificationConnected = value) } }

    var accessibilityConnected: Boolean
        get() = mutableState.value.accessibilityConnected
        set(value) { mutableState.update { it.copy(accessibilityConnected = value) } }
}
