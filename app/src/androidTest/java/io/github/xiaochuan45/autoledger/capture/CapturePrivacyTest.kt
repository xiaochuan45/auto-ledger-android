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
        context.getSharedPreferences("capture_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun leaveCaptureOff() { CapturePreferences(context).setEnabled(false) }

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

    private fun fictionalNotification(title: String): Notification =
        Notification.Builder(context, "fictional-local-test")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText("支付成功，实付 ¥23.50，商户：虚构餐馆")
            .build()

    private fun read(notification: Notification) = NotificationCaptureReader.read(
        PaymentChannel.WECHAT.packageName, notification, "fictional-stable-key", 1, 2)
}
