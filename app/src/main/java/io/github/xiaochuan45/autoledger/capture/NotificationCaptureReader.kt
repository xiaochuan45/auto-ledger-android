package io.github.xiaochuan45.autoledger.capture

import android.app.Notification
import io.github.xiaochuan45.autoledger.core.CaptureInput
import io.github.xiaochuan45.autoledger.core.CaptureSource
import io.github.xiaochuan45.autoledger.core.CaptureApp
import io.github.xiaochuan45.autoledger.core.NotificationAdapters

/** Filtering happens before message bodies are read; title matching is not sender authentication. */
internal object NotificationCaptureReader {
    fun read(
        packageName: String,
        notification: Notification,
        key: String,
        postTime: Long,
        observedAt: Long,
    ): CaptureInput? {
        val app = CaptureApp.fromPackage(packageName) ?: return null
        val adapter = NotificationAdapters.forApp(app)
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return skip(CaptureSkip.NOTIFICATION_GROUP)
        val extras = notification.extras ?: return skip(CaptureSkip.NOTIFICATION_METADATA)
        // A contact can call themselves “微信支付”. Reject conversation/messaging evidence.
        if (notification.category == Notification.CATEGORY_MESSAGE ||
            extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name ||
            CONVERSATION_KEYS.any(extras::containsKey) ||
            notification.shortcutId != null
        ) return skip(CaptureSkip.NOTIFICATION_CONVERSATION)
        val titleValue = extras.getCharSequence(Notification.EXTRA_TITLE) ?: return skip(CaptureSkip.NOTIFICATION_METADATA)
        if (titleValue.length > MAX_TITLE_CHARACTERS) return skipIncomplete()
        val title = titleValue.toString().trim()
        if (!adapter.matches(title)) return skip(CaptureSkip.NOTIFICATION_TITLE)
        if (CaptureDiagnostics.compatibilityActive()) return skip(CaptureSkip.COMPATIBILITY_ONLY)

        val text = TransientTextBuffer()
        text.add(extras.getCharSequence(Notification.EXTRA_TEXT))
        text.add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (lines != null && lines.size > TransientTextBuffer.MAX_FRAGMENTS) return skipIncomplete()
        lines?.forEach(text::add)
        if (text.incomplete) return skipIncomplete()
        val fragments = text.toList()
        if (fragments.isEmpty()) return skip(CaptureSkip.EMPTY_TEXT)
        if (fragments.any(::hasSensitiveText)) return skip(CaptureSkip.SENSITIVE_GUARD)
        return CaptureInput(
            source = adapter.source,
            packageName = packageName,
            texts = fragments,
            observedAt = observedAt,
            sourceKey = "notification:$key:$postTime",
            title = title,
        )
    }

    private fun skip(reason: CaptureSkip): Nothing? {
        CaptureDiagnostics.skipped(reason, CaptureSource.NOTIFICATION)
        return null
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
