package io.github.xiaochuan45.autoledger.capture

import android.app.Notification
import android.content.Context
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xiaochuan45.autoledger.core.PaymentChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic notifications only: nothing is posted and no third-party app is opened. */
@RunWith(AndroidJUnit4::class)
class CapturePrivacyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun clearSettings() {
        CaptureDiagnostics.clearCompatibilityCheck()
        context.getSharedPreferences("capture_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun leaveCaptureOff() {
        CapturePreferences(context).setEnabled(false)
        CaptureDiagnostics.clearCompatibilityCheck()
    }

    @Test fun enteringReadOnlyModeCancelsOneTimeTextReadWithoutRetainingFields() {
        CaptureDiagnostics.requestOneShotWechatReceipt()
        assertEquals(OneShotState.WAITING, CaptureDiagnostics.oneShotSnapshot().state)
        CaptureDiagnostics.startCompatibilityCheck()
        assertEquals(OneShotState.CANCELLED, CaptureDiagnostics.oneShotSnapshot().state)
        assertFalse(CaptureDiagnostics.takeOneShotWechatReceipt(PaymentChannel.WECHAT,
            io.github.xiaochuan45.autoledger.core.WechatWebReceipt.WINDOW))
    }

    @Test fun compatibilityCheckRejectsNotificationBeforeReadingItsBody() {
        val notification = fictionalNotification("微信支付")
        notification.extras.putCharSequence(Notification.EXTRA_TEXT, object : CharSequence {
            override val length: Int get() = error("Notification body must not be inspected in compatibility mode")
            override fun get(index: Int): Char = error("Body must not be inspected")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("Body must not be inspected")
            override fun toString(): String = error("Body must not be copied")
        })
        CaptureDiagnostics.startCompatibilityCheck()
        assertNull(read(notification))
        assertEquals(CaptureSkip.COMPATIBILITY_ONLY, CaptureDiagnostics.snapshot().lastSkip)
        assertFalse(CaptureDiagnostics.compatibilityReport("fictional-test", 35).contains("23.50"))
    }

    @Test fun metadataRejectionsAreVisibleWithoutKeepingConversationTitlesOrBodies() {
        val chat = fictionalNotification("虚构私密昵称")
        chat.category = Notification.CATEGORY_MESSAGE
        val before = CaptureDiagnostics.snapshot().skippedCount
        assertNull(read(chat))
        assertEquals(before + 1, CaptureDiagnostics.snapshot().skippedCount)
        assertEquals(CaptureSkip.NOTIFICATION_CONVERSATION, CaptureDiagnostics.snapshot().lastSkip)
        val report = CaptureDiagnostics.compatibilityReport("fictional-test", 35)
        assertFalse(report.contains("虚构私密昵称"))
        assertFalse(report.contains("23.50"))
    }

    @Test fun permissionsAloneCannotEnableCaptureAndChannelOptOutPersists() {
        val preferences = CapturePreferences(context)
        assertFalse(preferences.read().enabled)
        assertTrue(preferences.read().wechatEnabled)
        assertTrue(preferences.read().alipayEnabled)
        assertFalse(preferences.isCaptureEnabled(PaymentChannel.WECHAT))
        preferences.setEnabled(true)
        preferences.setChannelEnabled(PaymentChannel.WECHAT, false)
        val reopened = CapturePreferences(context)
        assertTrue(reopened.read().enabled)
        assertFalse(reopened.isCaptureEnabled(PaymentChannel.WECHAT))
        assertTrue(reopened.isCaptureEnabled(PaymentChannel.ALIPAY))
        preferences.setEnabled(false)
        assertFalse(reopened.isCaptureEnabled(PaymentChannel.ALIPAY))
    }

    @Test fun ordinaryChatCannotImpersonatePaymentTitleUsingConversationMetadata() {
        val metadata = listOf("android.messages", "android.people", "android.people.list",
            "android.conversationTitle", "android.messagingStyleUser")
        metadata.forEach { field ->
            val notification = fictionalNotification("微信支付")
            notification.extras.putString(field, "fictional conversation metadata")
            assertNull(read(notification))
        }
        val categorizedChat = fictionalNotification("微信支付")
        categorizedChat.category = Notification.CATEGORY_MESSAGE
        assertNull(read(categorizedChat))
        val messagingStyle = fictionalNotification("微信支付")
        messagingStyle.extras.putString(Notification.EXTRA_TEMPLATE, Notification.MessagingStyle::class.java.name)
        assertNull(read(messagingStyle))
    }

    @Test fun unrelatedPackageTitleGroupAndVerificationAreDropped() {
        assertNull(read(fictionalNotification("虚构好友")))
        assertNull(NotificationCaptureReader.read("com.example.fictional", fictionalNotification("微信支付"), "fake", 1, 2))
        val group = fictionalNotification("微信支付")
        group.flags = group.flags or Notification.FLAG_GROUP_SUMMARY
        assertNull(read(group))
        val verification = fictionalNotification("支付宝")
        verification.extras.putCharSequence(Notification.EXTRA_TEXT, "验证码 000000，请勿泄露")
        assertNull(NotificationCaptureReader.read(PaymentChannel.ALIPAY.packageName, verification, "fake", 1, 2))
    }

    @Test fun onlyPaymentFieldsAreExposedAndNotificationKeyRemainsStable() {
        val notification = fictionalNotification("微信支付")
        notification.extras.putCharSequence("android.secret.fictional", "must not be copied")
        val input = read(notification)
        assertNotNull(input)
        assertTrue(input!!.texts.sumOf(String::length) <= 4_000)
        assertTrue(input.texts.size <= 24)
        assertFalse(input.texts.any { it.contains("must not be copied") })
        assertEquals(input.sourceKey, read(notification)!!.sourceKey)
    }

    @Test fun oversizedEvidenceCannotHideFailureAfterSuccessfulPrefix() {
        val notification = fictionalNotification("微信支付")
        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT,
            "支付成功 支付金额23.50元" + "x".repeat(4_000) + "支付失败")
        val before = CaptureDiagnostics.snapshot().failureCount
        assertNull(read(notification))
        val after = CaptureDiagnostics.snapshot()
        assertEquals(before + 1, after.failureCount)
        assertEquals(CaptureFailure.SKIPPED_INCOMPLETE, after.lastFailure)
    }

    @Test fun notificationLinesAfterFragmentLimitCannotBeSilentlyOmitted() {
        val notification = fictionalNotification("微信支付")
        notification.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES,
            Array<CharSequence>(25) { if (it == 24) "支付失败" else "虚构行 $it" })
        val before = CaptureDiagnostics.snapshot().failureCount
        assertNull(read(notification))
        assertEquals(before + 1, CaptureDiagnostics.snapshot().failureCount)
        assertEquals(CaptureFailure.SKIPPED_INCOMPLETE, CaptureDiagnostics.snapshot().lastFailure)
    }

    @Test fun permissionSnapshotMatchesUnprivilegedStateWithoutRequestingAccess() {
        // The same snapshot path runs on API 26 (secure-setting fallback) and current Android.
        val snapshot = PermissionHelper.snapshot(context)
        val component = android.content.ComponentName(context, LedgerNotificationService::class.java)
        val expected = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.any { android.content.ComponentName.unflattenFromString(it) == component } == true
        assertEquals(expected, snapshot.notificationAccess)
        if (!snapshot.notificationAccess) assertFalse(snapshot.notificationConnected)
        if (!snapshot.accessibilityAccess) assertFalse(snapshot.accessibilityConnected)
    }

    @Test fun recoveryCannotBypassOptInAndOnlyConnectionCallbackUpdatesLiveState() {
        val preferences = CapturePreferences(context)
        val beforeRequests = CaptureDiagnostics.snapshot().notificationReconnectRequests
        val result = PermissionHelper.recoverNotifications(context)
        assertTrue(result == NotificationRecoveryResult.ACCESS_NOT_GRANTED ||
            result == NotificationRecoveryResult.CAPTURE_DISABLED)
        assertEquals(beforeRequests, CaptureDiagnostics.snapshot().notificationReconnectRequests)

        preferences.setEnabled(true)
        preferences.setChannelEnabled(PaymentChannel.WECHAT, false)
        preferences.setChannelEnabled(PaymentChannel.ALIPAY, false)
        val noChannels = PermissionHelper.maybeRecoverNotificationsOnForeground(context)
        assertTrue(noChannels == NotificationRecoveryResult.ACCESS_NOT_GRANTED ||
            noChannels == NotificationRecoveryResult.CAPTURE_DISABLED)
        assertEquals(beforeRequests, CaptureDiagnostics.snapshot().notificationReconnectRequests)

        val previous = CaptureServiceConnections.notificationConnected
        try {
            CaptureServiceConnections.notificationConnected = false
            assertFalse(PermissionHelper.connectionState.value.notificationConnected)
            // Exercise the callback implementation without binding to or reading any notification.
            LedgerNotificationService().onListenerConnected()
            assertTrue(PermissionHelper.connectionState.value.notificationConnected)
        } finally {
            CaptureServiceConnections.notificationConnected = previous
            preferences.setChannelEnabled(PaymentChannel.WECHAT, true)
            preferences.setChannelEnabled(PaymentChannel.ALIPAY, true)
        }
    }

    private fun fictionalNotification(title: String): Notification =
        Notification.Builder(context, "fictional-local-test")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText("支付成功，实付 ¥23.50，商户：虚构餐馆")
            .build()

    private fun read(notification: Notification) = NotificationCaptureReader.read(
        PaymentChannel.WECHAT.packageName, notification, "fictional-stable-key", 1, 2)
}
