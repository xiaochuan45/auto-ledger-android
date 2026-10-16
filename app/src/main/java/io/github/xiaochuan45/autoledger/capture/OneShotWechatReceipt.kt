package io.github.xiaochuan45.autoledger.capture

import io.github.xiaochuan45.autoledger.core.PaymentChannel
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt

enum class OneShotState(val label: String) { NONE("未请求"), WAITING("等待打开微信详情，最多30秒"), USED("已尝试一次"), EXPIRED("已超时"), CANCELLED("已取消") }
data class OneShotSnapshot(val state: OneShotState, val remainingMillis: Long, val attempts: Int)

/** Explicit, process-only, single attempt; never starts a timer, reader or background task. */
internal class OneShotWechatReceipt(private val clock: () -> Long) {
    private var started: Long? = null
    private var state = OneShotState.NONE
    private var attempts = 0
    @Synchronized fun arm() { started = clock(); state = OneShotState.WAITING }
    @Synchronized fun cancel() { if (state == OneShotState.WAITING) state = OneShotState.CANCELLED; started = null }
    @Synchronized fun snapshot(): OneShotSnapshot {
        val elapsed = started?.let { clock() - it }
        if (state == OneShotState.WAITING && (elapsed == null || elapsed !in 0 until DURATION)) {
            state = OneShotState.EXPIRED; started = null
        }
        return OneShotSnapshot(state, if (state == OneShotState.WAITING) DURATION - (elapsed ?: DURATION) else 0, attempts)
    }
    @Synchronized fun consume(channel: PaymentChannel, screenClass: String): Boolean {
        if (!WechatWebReceipt.isCandidate(channel, screenClass) || snapshot().state != OneShotState.WAITING) return false
        state = OneShotState.USED; started = null
        if (attempts < Int.MAX_VALUE) attempts++
        return true
    }
    private companion object { const val DURATION = 30_000L }
}
