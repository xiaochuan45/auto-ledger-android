package io.github.xiaochuan45.autoledger.capture

import io.github.xiaochuan45.autoledger.core.PaymentChannel
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt

enum class OneShotState(val label: String) { NONE("未请求"), WAITING("等待打开微信详情，最多30秒"), USED("已尝试一次"), EXPIRED("已超时"), CANCELLED("已取消") }
enum class OneShotStage(val label: String) {
    WAITING("等待入口事件"), CHECKING("检查当前窗口栏目"), ROOT_MISSING("系统未提供当前根窗口"),
    WINDOW_CHANGED("根窗口与入口不一致"), INCOMPLETE("栏目未齐全，等待后续事件"),
    BUDGET("已达本次窗口检查上限"), READING("栏目通过，已尝试正文读取"),
}
data class OneShotSnapshot(val state: OneShotState, val remainingMillis: Long, val attempts: Int,
    val windowTypes: List<String> = emptyList(), val windowChecks: Int = 0,
    val stage: OneShotStage = OneShotStage.WAITING)

/** Explicit, process-only, single attempt; never starts a timer, reader or background task. */
internal class OneShotWechatReceipt(private val clock: () -> Long) {
    private var started: Long? = null
    private var state = OneShotState.NONE
    private var attempts = 0
    private val windowTypes = ArrayDeque<String>()
    private var windowChecks = 0
    private var lastCheck: Long? = null
    private var stage = OneShotStage.WAITING
    @Synchronized fun arm() { started = clock(); state = OneShotState.WAITING; windowTypes.clear(); windowChecks = 0; lastCheck = null; stage = OneShotStage.WAITING }
    @Synchronized fun window(channel: PaymentChannel, className: String) {
        if (snapshot().state != OneShotState.WAITING || channel != PaymentChannel.WECHAT ||
            className.length > 200 || !className.startsWith(channel.packageName + ".") ||
            !Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+").matches(className)) return
        windowTypes.remove(className)
        if (windowTypes.size == 6) windowTypes.removeFirst()
        windowTypes.addLast(className)
    }
    @Synchronized fun cancel() { if (state == OneShotState.WAITING) state = OneShotState.CANCELLED; started = null }
    @Synchronized fun snapshot(): OneShotSnapshot {
        val elapsed = started?.let { clock() - it }
        if (state == OneShotState.WAITING && (elapsed == null || elapsed !in 0 until DURATION)) {
            state = OneShotState.EXPIRED; started = null
        }
        return OneShotSnapshot(state, if (state == OneShotState.WAITING) DURATION - (elapsed ?: DURATION) else 0, attempts,
            windowTypes.toList(), windowChecks, stage)
    }
    /** Event-driven metadata/column checks only. No timer, body or retry after consumption. */
    @Synchronized fun beginWindowCheck(channel: PaymentChannel, screenClass: String): Boolean {
        if (!WechatWebReceipt.isMiniCandidate(channel, screenClass) || snapshot().state != OneShotState.WAITING) return false
        if (windowChecks >= MAX_WINDOW_CHECKS) { stage = OneShotStage.BUDGET; return false }
        val now = clock()
        if (lastCheck?.let { now - it < MIN_CHECK_INTERVAL } == true) return false
        lastCheck = now; windowChecks++; stage = OneShotStage.CHECKING
        return true
    }
    @Synchronized fun noteStage(value: OneShotStage) { stage = value }
    @Synchronized fun consume(channel: PaymentChannel, screenClass: String): Boolean {
        if (!WechatWebReceipt.isExplicitCandidate(channel, screenClass) || snapshot().state != OneShotState.WAITING) return false
        state = OneShotState.USED; started = null; stage = OneShotStage.READING
        if (attempts < Int.MAX_VALUE) attempts++
        return true
    }
    private companion object { const val DURATION = 30_000L; const val MAX_WINDOW_CHECKS = 8; const val MIN_CHECK_INTERVAL = 500L }
}
