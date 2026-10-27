package io.github.xiaochuan45.autoledger.capture

import android.app.Notification
import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xiaochuan45.autoledger.core.*
import io.github.xiaochuan45.autoledger.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Fictional messages only; never posts a notification or opens a third-party app. */
@RunWith(AndroidJUnit4::class)
class MultiAppCaptureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = 1_800_000_000_000L
    @Before fun reset() {
        CaptureDiagnostics.clearCompatibilityCheck()
        context.getSharedPreferences("capture_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun close() { CapturePreferences(context).setEnabled(false) }

    @Test fun shoppingAppSourcesAreIndependentDefaultOffAndCannotBypassMasterSwitch() {
        val prefs = CapturePreferences(context)
        CaptureApp.platformApps.forEach { assertFalse(prefs.isAppEnabled(it)) }
        prefs.setPlatformEnabled(CaptureApp.PINDUODUO, true)
        assertFalse(prefs.isAppEnabled(CaptureApp.PINDUODUO))
        prefs.setEnabled(true)
        assertTrue(CapturePreferences(context).isAppEnabled(CaptureApp.PINDUODUO))
        assertFalse(prefs.isAppEnabled(CaptureApp.JINGDONG))
        prefs.setChannelEnabled(PaymentChannel.WECHAT, false)
        prefs.setChannelEnabled(PaymentChannel.ALIPAY, false)
        assertTrue(prefs.hasNotificationSources())
        prefs.setPlatformEnabled(CaptureApp.PINDUODUO, false)
        assertFalse(prefs.hasNotificationSources())
    }

    @Test fun unsupportedPlatformNotificationIsRejectedBeforeItsBodyIsTouched() {
        val notice = notice("限时红包", "")
        notice.extras.putCharSequence(Notification.EXTRA_TEXT, object : CharSequence {
            override val length: Int get() = error("Marketing body must not be read")
            override fun get(index: Int): Char = error("No body")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("No body")
        })
        assertNull(NotificationCaptureReader.read(CaptureApp.PINDUODUO.packages.first(), notice, "fictional", now, now))
    }

    @Test fun selectedPlatformNotificationUsesSupplementaryRouteAndUnknownPaymentStaysUnknown() {
        val input = requireNotNull(NotificationCaptureReader.read(CaptureApp.MEITUAN.packages.last(),
            notice("支付成功", "实付金额：42.70元"), "fictional-platform", now, now))
        assertEquals(CaptureSource.PLATFORM_NOTIFICATION, input.source)
        val parsed = requireNotNull(PaymentParser.parse(input))
        assertEquals(PaymentChannel.UNKNOWN, parsed.channel)
        assertEquals("美团", parsed.platform)
        assertEquals(RecordStatus.REVIEW, parsed.status)
    }

    @Test fun primaryFirstPlatformCanFillMissingPlatformButNeverOverwriteFinancialFields() = database { repo ->
        val primary = requireNotNull(PaymentParser.parse(primaryInput()))
        val original = repo.ingest(primary)
        val supplemental = requireNotNull(PaymentParser.parse(platformInput()))
        val linked = repo.ingest(supplemental)
        assertEquals(original.recordId, linked.recordId)
        val bill = requireNotNull(repo.getRecord(original.recordId))
        assertEquals("拼多多", bill.platform)
        assertEquals(4270L, bill.amountMinor)
        assertEquals("虚构收款商户", bill.merchant)
        assertEquals(RecordStatus.CONFIRMED.name, bill.status)
        assertEquals(1, repo.records.first().size)
        repo.ingest(supplemental.copy(captureKey = "fictional-conflict", amountMinor = 100L))
        assertEquals(bill, repo.getRecord(original.recordId))
    }

    @Test fun platformFirstWaitsForPrimaryEvidenceAndPrimaryMerchantWins() = database { repo ->
        val secondary = requireNotNull(PaymentParser.parse(platformInput()))
        val first = repo.ingest(secondary)
        assertEquals(RecordStatus.REVIEW.name, repo.getRecord(first.recordId)!!.status)
        val primary = requireNotNull(PaymentParser.parse(primaryInput()))
        assertEquals(first.recordId, repo.ingest(primary).recordId)
        val bill = requireNotNull(repo.getRecord(first.recordId))
        assertEquals("虚构收款商户", bill.merchant)
        assertEquals(RecordStatus.CONFIRMED.name, bill.status)
        assertEquals("拼多多", bill.platform)
        assertEquals(1, repo.records.first().size)
    }

    @Test fun userCorrectionsCannotBeOverwrittenByPlatformNotifications() = database { repo ->
        val id = repo.ingest(requireNotNull(PaymentParser.parse(primaryInput()))).recordId
        repo.update(id, LedgerDraft(4270, now, merchant = "用户填写商户", platform = "用户填写平台"))
        val corrected = repo.getRecord(id)
        repo.ingest(requireNotNull(PaymentParser.parse(platformInput())))
        assertEquals(corrected, repo.getRecord(id))
    }

    @Test fun unknownChannelCannotCreateAStrongPaymentIdentityAndDifferentRealIdsStaySeparate() = database { repo ->
        val parsed = requireNotNull(PaymentParser.parse(platformInput()))
        repo.ingest(parsed.copy(channel = PaymentChannel.UNKNOWN, transactionId = "COLLIDING-ORDER", captureKey = "unknown-a"))
        repo.ingest(parsed.copy(channel = PaymentChannel.UNKNOWN, transactionId = "COLLIDING-ORDER", captureKey = "unknown-b"))
        assertEquals(2, repo.records.first().size)
        assertTrue(repo.records.first().all { it.strongKey == null && it.status == RecordStatus.REVIEW.name })
        repo.ingest(parsed.copy(transactionId = "FICTIONAL-TRUE-PAY-A", captureKey = "real-a"))
        repo.ingest(parsed.copy(transactionId = "FICTIONAL-TRUE-PAY-B", captureKey = "real-b"))
        assertEquals(4, repo.records.first().size)
    }

    private fun primaryInput() = CaptureInput(CaptureSource.NOTIFICATION, PaymentChannel.WECHAT.packageName,
        listOf("支付成功\n实付金额：42.70元\n商户名称：虚构收款商户\n交易单号：FICTIONAL-SHARED-WX-001"), now, "fictional-primary", title = "微信支付")
    private fun platformInput() = CaptureInput(CaptureSource.PLATFORM_NOTIFICATION, CaptureApp.PINDUODUO.packages.first(),
        listOf("支付成功\n实付金额：42.70元\n商户名称：虚构平台描述\n支付方式：微信支付\n微信支付交易单号：FICTIONAL-SHARED-WX-001"), now, "fictional-platform", title = "支付成功")
    private fun notice(title: String, body: String) = Notification().apply {
        extras = android.os.Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, title)
            putCharSequence(Notification.EXTRA_TEXT, body)
        }
    }
    private fun database(block: suspend (LedgerRepository) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try { block(LedgerRepository(db) { now }) } finally { db.close() }
    }
}
