package io.github.xiaochuan45.autoledger.capture

import android.content.Context
import android.content.SharedPreferences
import io.github.xiaochuan45.autoledger.core.PaymentChannel

data class CaptureSettings(
    val enabled: Boolean = false,
    val wechatEnabled: Boolean = true,
    val alipayEnabled: Boolean = true,
)

/** Recognition requires an explicit opt-in; the OS permission alone never enables it. */
class CapturePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun read(): CaptureSettings = CaptureSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        wechatEnabled = preferences.getBoolean(KEY_WECHAT, true),
        alipayEnabled = preferences.getBoolean(KEY_ALIPAY, true),
    )

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setChannelEnabled(channel: PaymentChannel, enabled: Boolean) {
        preferences.edit().putBoolean(channel.preferenceKey, enabled).apply()
    }

    fun isCaptureEnabled(channel: PaymentChannel): Boolean {
        val settings = read()
        return settings.enabled && when (channel) {
            PaymentChannel.WECHAT -> settings.wechatEnabled
            PaymentChannel.ALIPAY -> settings.alipayEnabled
        }
    }

    internal fun observeChanges(block: () -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> block() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private val PaymentChannel.preferenceKey: String
        get() = when (this) {
            PaymentChannel.WECHAT -> KEY_WECHAT
            PaymentChannel.ALIPAY -> KEY_ALIPAY
        }

    private companion object {
        const val NAME = "capture_preferences"
        const val KEY_ENABLED = "capture_enabled"
        const val KEY_WECHAT = "wechat_enabled"
        const val KEY_ALIPAY = "alipay_enabled"
    }
}
