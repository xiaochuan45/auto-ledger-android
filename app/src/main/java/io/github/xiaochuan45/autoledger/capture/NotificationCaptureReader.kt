package io.github.xiaochuan45.autoledger.capture

import android.app.Notification
import io.github.xiaochuan45.autoledger.core.CaptureInput
import io.github.xiaochuan45.autoledger.core.CapturePolicy
import io.github.xiaochuan45.autoledger.core.CaptureSource
import io.github.xiaochuan45.autoledger.core.PaymentChannel

/** Filtering happens before message bodies are read; title matching is not sender authentication. */
internal object NotificationCaptureReader {
    fun read(
        packageName: String,
        notification: Notification,
        key: String,
        postTime: Long,
        observedAt: Long,
    ): CaptureInput? {
        val channel = PaymentChannel.fromPackage(packageName) ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = notification.extras ?: return null
        // A contact can call themselves “微信支付”. Reject conversation/messaging evidence.
        if (notification.category == Notification.CATEGORY_MESSAGE ||
            extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name ||
            CONVERSATION_KEYS.any(extras::containsKey) ||
            notification.shortcutId != null
        ) return null
        val titleValue = extras.getCharSequence(Notification.EXTRA_TITLE) ?: return null
        if (titleValue.length > MAX_TITLE_CHARACTERS) return skipIncomplete()
        val title = titleValue.toString().trim()
        if (!CapturePolicy.isTrustedNotificationTitle(channel, title)) return null

        val text = TransientTextBuffer()
        text.add(extras.getCharSequence(Notification.EXTRA_TEXT))
        text.add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (lines != null && lines.size > TransientTextBuffer.MAX_FRAGMENTS) return skipIncomplete()
        lines?.forEach(text::add)
        if (text.incomplete) return skipIncomplete()
        val fragments = text.toList()
        if (fragments.isEmpty() || fragments.any(::hasSensitiveText)) return null
        return CaptureInput(
            source = CaptureSource.NOTIFICATION,
            packageName = channel.packageName,
            texts = fragments,
            observedAt = observedAt,
            sourceKey = "notification:$key:$postTime",
            title = title,
        )
    }

    private fun skipIncomplete(): Nothing? {
        CaptureDiagnostics.failed(CaptureFailure.SKIPPED_INCOMPLETE)
        return null
    }

    private const val MAX_TITLE_CHARACTERS = 120
    private val CONVERSATION_KEYS = listOf(
        Notification.EXTRA_MESSAGES,
        Notification.EXTRA_PEOPLE,
        Notification.EXTRA_PEOPLE_LIST,
        "android.conversationTitle",
        "android.messagingStyleUser",
        "android.messagingUser",
        "android.messages.historic",
    )
}
