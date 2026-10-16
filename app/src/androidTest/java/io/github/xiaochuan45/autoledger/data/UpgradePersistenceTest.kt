package io.github.xiaochuan45.autoledger.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xiaochuan45.autoledger.core.CaptureSource
import io.github.xiaochuan45.autoledger.core.EntryKind
import io.github.xiaochuan45.autoledger.core.LedgerDraft
import io.github.xiaochuan45.autoledger.core.ParsedPayment
import io.github.xiaochuan45.autoledger.core.PaymentChannel
import io.github.xiaochuan45.autoledger.core.RecordStatus
import io.github.xiaochuan45.autoledger.core.TimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run methods separately with a real `adb install -r` between them.
 * Exclude this class from ordinary test suites. Two sequential test methods alone are NOT upgrade evidence.
 * The fixture exercises preservation of schema v1; it does not test a future database migration.
 */
@RunWith(AndroidJUnit4::class)
class UpgradePersistenceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = 1_792_152_000_000L

    @Test fun seedBeforeUpgrade() = runBlocking {
        context.deleteDatabase("ledger.db")
        val database = open()
        try {
            val repository = LedgerRepository(database) { now }
            val first = repository.ingest(payment("upgrade-expense", "SYNTHETIC-UPGRADE-EXPENSE"))
            repository.update(first.recordId, LedgerDraft(amountMinor = 1234, occurredAt = now,
                merchant = "虚构升级商户", platform = "虚构平台", category = "餐饮", note = "仅自动化升级测试"))
            repository.ingest(payment("upgrade-review", "SYNTHETIC-UPGRADE-REVIEW").copy(
                amountMinor = null, status = RecordStatus.REVIEW, reviewReason = "虚构待核对"))
            val deleted = repository.ingest(payment("upgrade-deleted", "SYNTHETIC-UPGRADE-DELETED"))
            repository.delete(deleted.recordId)
            assertEquals(2, repository.records.first().size)
            assertEquals(3, database.ledgerDao().allRecords().size)
        } finally {
            database.close()
        }
    }

    @Test fun dataSurvivesUpgrade() = runBlocking {
        val database = open()
        try {
            val repository = LedgerRepository(database) { now }
            val all = database.ledgerDao().allRecords()
            assertEquals("Run seedBeforeUpgrade before installing the update APK", 3, all.size)
            val expense = all.single { it.transactionId == "SYNTHETIC-UPGRADE-EXPENSE" }
            assertEquals(1234L, expense.amountMinor)
            assertEquals("虚构升级商户", expense.merchant)
            assertEquals("餐饮", expense.category)
            assertEquals("仅自动化升级测试", expense.note)
            assertEquals(RecordStatus.CONFIRMED.name, expense.status)
            assertEquals(TimeSource.PAYMENT.name, expense.timeSource)
            assertTrue(expense.userEdited)
            assertEquals(RecordStatus.REVIEW.name,
                all.single { it.transactionId == "SYNTHETIC-UPGRADE-REVIEW" }.status)
            assertEquals(IngestOutcome.DUPLICATE,
                repository.ingest(payment("upgrade-expense", "SYNTHETIC-UPGRADE-EXPENSE")).outcome)
            assertEquals(IngestOutcome.SUPPRESSED_DELETED,
                repository.ingest(payment("upgrade-deleted", "SYNTHETIC-UPGRADE-DELETED")).outcome)
            assertEquals(IngestOutcome.SUPPRESSED_DELETED,
                repository.ingest(payment("upgrade-deleted-new-source", "SYNTHETIC-UPGRADE-DELETED")).outcome)
            assertEquals(2, repository.records.first().size)
            assertEquals(3, database.ledgerDao().allRecords().size)
        } finally {
            database.close()
            context.deleteDatabase("ledger.db")
        }
    }

    private fun open() = Room.databaseBuilder(context, LedgerDatabase::class.java, "ledger.db").build()

    private fun payment(capture: String, transaction: String) = ParsedPayment(
        amountMinor = 2350, channel = PaymentChannel.WECHAT, merchant = "虚构升级商户", platform = "虚构平台",
        occurredAt = now, timeSource = TimeSource.PAYMENT, kind = EntryKind.EXPENSE,
        status = RecordStatus.CONFIRMED, transactionId = transaction, captureKey = capture,
        contentFingerprint = "synthetic-upgrade-fingerprint", source = CaptureSource.NOTIFICATION,
    )
}
