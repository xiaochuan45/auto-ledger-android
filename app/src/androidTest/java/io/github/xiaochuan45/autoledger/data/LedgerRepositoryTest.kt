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
import io.github.xiaochuan45.autoledger.core.CaptureInput
import io.github.xiaochuan45.autoledger.core.PaymentParser
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic evidence only. These tests do not claim that payment apps expose these fields. */
@RunWith(AndroidJUnit4::class)
class LedgerRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var databaseName: String
    private val now = 1_792_152_000_000L

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "ledger-test-${UUID.randomUUID()}.db"
        open()
    }

    private fun open() {
        database = Room.databaseBuilder(context, LedgerDatabase::class.java, databaseName).build()
        repository = LedgerRepository(database) { now }
    }

    @After fun cleanup() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun sameTransactionAcrossSourcesIsOnePersistentRecord() = runBlocking {
        val first = repository.ingest(payment())
        repository.ingest(payment(capture = "screen-1", source = CaptureSource.ACCESSIBILITY))
        assertEquals(1, repository.records.first().size)
        database.close()
        open()
        val replay = repository.ingest(payment(capture = "screen-after-reopen", source = CaptureSource.ACCESSIBILITY))
        assertEquals(first.recordId, replay.recordId)
        val record = repository.records.first().single()
        assertEquals("ACCESSIBILITY,NOTIFICATION", record.sourceTypes)
        assertEquals(2350L, record.amountMinor)
        assertEquals(RecordStatus.CONFIRMED.name, record.status)
    }

    @Test fun webReceiptReopensStayOneReviewAndDistinctSameAmountTradeRemainsIndependent() = runBlocking {
        fun web(id: String, key: String): ParsedPayment = requireNotNull(PaymentParser.parse(CaptureInput(
            CaptureSource.ACCESSIBILITY, PaymentChannel.WECHAT.packageName,
            listOf("虚构商店", "-42.70", "当前状态", "支付成功", "支付时间", "2025年3月12日 10:20:30",
                "支付方式", "零钱", "交易单号", id, "商户单号", "FICTIONAL-ORDER", "收单机构", "虚构机构", "账单服务"),
            now, key, screenClass = WechatWebReceipt.WINDOW)))
        val first = repository.ingest(web("FICTIONAL-WEB-A", "fictional-first"))
        database.close()
        open()
        val replay = repository.ingest(web("FICTIONAL-WEB-A", "fictional-reopen"))
        assertEquals(first.recordId, replay.recordId)
        val second = repository.ingest(web("FICTIONAL-WEB-B", "fictional-second"))
        assertNotEquals(first.recordId, second.recordId)
        assertEquals(2, repository.records.first().size)
        assertTrue(repository.records.first().all { it.status == RecordStatus.REVIEW.name && it.amountMinor == 4270L })
        assertTrue(repository.records.first().none { it.status == RecordStatus.CONFIRMED.name })
    }

    @Test fun unverifiedWebEvidenceCannotChangeAnExistingBillOrBypassReview() = runBlocking {
        val original = repository.ingest(payment(transaction = "FICTIONAL-WEB-EXISTING"))
        val before = repository.getRecord(original.recordId)
        repository.ingest(payment(capture = "fictional-web-existing", transaction = "FICTIONAL-WEB-EXISTING")
            .copy(source = CaptureSource.WEB_RECEIPT, amountMinor = 99999, merchant = "虚构未验证商户",
                status = RecordStatus.REVIEW, reviewReason = WechatWebReceipt.REVIEW_REASON))
        assertEquals(before, repository.getRecord(original.recordId))
        val untrusted = repository.ingest(payment(capture = "fictional-web-forced-confirm", transaction = "FICTIONAL-WEB-NEW")
            .copy(source = CaptureSource.WEB_RECEIPT, status = RecordStatus.CONFIRMED, reviewReason = null))
        assertEquals(RecordStatus.REVIEW.name, repository.getRecord(untrusted.recordId)?.status)
        assertEquals(WechatWebReceipt.REVIEW_REASON, repository.getRecord(untrusted.recordId)?.reviewReason)
    }

    @Test fun differentStrongIdsKeepConsecutiveSameAmountPaymentsIndependent() = runBlocking {
        val first = repository.ingest(payment(transaction = "SYNTHETIC-A"))
        val second = repository.ingest(payment(capture = "notification-2", transaction = "SYNTHETIC-B",
            source = CaptureSource.ACCESSIBILITY))
        assertNotEquals(first.recordId, second.recordId)
        assertEquals(2, repository.records.first().size)
        assertTrue(repository.records.first().all { it.status == RecordStatus.CONFIRMED.name && it.possibleDuplicateOf == null })
    }

    @Test fun correctingTransferToExpenseDoesNotChangePaymentIdentity() = runBlocking {
        val first = repository.ingest(payment(transaction = "SYNTHETIC-CORRECTED-KIND").copy(kind = EntryKind.TRANSFER))
        assertEquals(RecordStatus.REVIEW.name, repository.getRecord(first.recordId)?.status)
        val beforeCorrection = repository.ingest(payment(capture = "expense-before-kind-edit",
            transaction = "SYNTHETIC-CORRECTED-KIND", source = CaptureSource.ACCESSIBILITY))
        assertEquals(first.recordId, beforeCorrection.recordId)
        assertEquals(RecordStatus.REVIEW.name, repository.getRecord(first.recordId)?.status)
        repository.confirm(first.recordId, draft().copy(kind = EntryKind.EXPENSE, note = "虚构转账用途已确认为消费"))

        val recognizedAgain = repository.ingest(payment(capture = "expense-after-kind-edit",
            transaction = "SYNTHETIC-CORRECTED-KIND", source = CaptureSource.ACCESSIBILITY))
        assertEquals(first.recordId, recognizedAgain.recordId)
        val retained = repository.records.first().single()
        assertEquals(RecordStatus.CONFIRMED.name, retained.status)
        assertEquals(EntryKind.EXPENSE.name, retained.kind)
        assertEquals("虚构转账用途已确认为消费", retained.note)
        assertTrue(retained.userEdited)

        // A genuinely different identifier remains an independent same-amount payment.
        val distinct = repository.ingest(payment(capture = "distinct-payment-after-kind-edit",
            transaction = "SYNTHETIC-DIFFERENT-REAL-PAYMENT", source = CaptureSource.ACCESSIBILITY))
        assertNotEquals(first.recordId, distinct.recordId)
        assertEquals(2, repository.records.first().count { it.status == RecordStatus.CONFIRMED.name })
    }

    @Test fun refundCannotUseOriginalPaymentIdToMergePaymentOrOtherRefunds() = runBlocking {
        val expense = repository.ingest(payment())
        val firstRefund = repository.ingest(payment(capture = "partial-refund-one").copy(kind = EntryKind.REFUND, amountMinor = 500))
        val secondRefund = repository.ingest(payment(capture = "partial-refund-two").copy(kind = EntryKind.REFUND, amountMinor = 500))
        assertNotEquals(expense.recordId, firstRefund.recordId)
        assertNotEquals(firstRefund.recordId, secondRefund.recordId)
        assertEquals(3, repository.records.first().size)
        listOf(firstRefund, secondRefund).forEach { result ->
            val refund = requireNotNull(repository.getRecord(result.recordId))
            assertEquals(RecordStatus.REVIEW.name, refund.status)
            assertNull(refund.transactionId)
            assertNull(refund.strongKey)
        }
    }

    @Test fun sameCaptureKeyRemainsDeduplicatedAfterDatabaseReopen() = runBlocking {
        val sample = payment(transaction = null)
        val first = repository.ingest(sample)
        database.close()
        open()
        val replay = repository.ingest(sample)
        assertEquals(IngestOutcome.DUPLICATE, replay.outcome)
        assertEquals(first.recordId, replay.recordId)
        assertEquals(1, repository.records.first().size)
    }

    @Test fun weakCrossSourceMatchIsReviewAndCanBeExplicitlyKeptSeparate() = runBlocking {
        val first = repository.ingest(payment(transaction = null))
        val second = repository.ingest(payment(capture = "screen-no-id", transaction = null, source = CaptureSource.ACCESSIBILITY))
        val review = requireNotNull(repository.getRecord(second.recordId))
        assertEquals(RecordStatus.REVIEW.name, review.status)
        assertEquals(first.recordId, review.possibleDuplicateOf)
        assertEquals(2, repository.records.first().size)
        repository.confirm(second.recordId, draft())
        val confirmed = requireNotNull(repository.getRecord(second.recordId))
        assertEquals(RecordStatus.CONFIRMED.name, confirmed.status)
        assertNull(confirmed.possibleDuplicateOf)
        assertEquals(2, repository.records.first().size)
    }

    @Test fun weakSameSourceResendIsRetainedForReviewRatherThanCountedTwice() = runBlocking {
        val first = repository.ingest(payment(transaction = null))
        val second = repository.ingest(payment(capture = "new-notification-key", transaction = null))
        assertNotEquals(first.recordId, second.recordId)
        val records = repository.records.first()
        assertEquals(2, records.size)
        assertEquals(1, records.count { it.status == RecordStatus.CONFIRMED.name })
        assertEquals(RecordStatus.REVIEW.name, repository.getRecord(second.recordId)?.status)
    }

    @Test fun userEditsAreNotOverwrittenByRecognitionAndPreserveUnchangedTimeSource() = runBlocking {
        val first = repository.ingest(payment())
        repository.update(first.recordId, draft().copy(amountMinor = 2200, merchant = "虚构修正商户", category = "餐饮", note = "虚构备注"))
        repository.ingest(payment(capture = "after-user-edit", source = CaptureSource.ACCESSIBILITY))
        val record = requireNotNull(repository.getRecord(first.recordId))
        assertEquals(2200L, record.amountMinor)
        assertEquals("虚构修正商户", record.merchant)
        assertEquals("餐饮", record.category)
        assertEquals("虚构备注", record.note)
        assertEquals(TimeSource.PAYMENT.name, record.timeSource)
        assertTrue(record.userEdited)
        repository.update(first.recordId, draft().copy(occurredAt = now - 60_000))
        assertEquals(TimeSource.USER.name, repository.getRecord(first.recordId)?.timeSource)
    }

    @Test fun deletedCapturesAndStrongIdsStaySuppressedUntilExplicitRestore() = runBlocking {
        val sample = payment()
        val first = repository.ingest(sample)
        repository.delete(first.recordId)
        database.close()
        open()
        assertTrue(repository.records.first().isEmpty())
        assertEquals(IngestOutcome.SUPPRESSED_DELETED, repository.ingest(sample).outcome)
        assertEquals(IngestOutcome.SUPPRESSED_DELETED,
            repository.ingest(payment(capture = "new-page-after-delete", source = CaptureSource.ACCESSIBILITY)).outcome)
        assertTrue(repository.records.first().isEmpty())
        repository.restore(first.recordId)
        assertEquals(1, repository.records.first().size)
        assertEquals(first.recordId, repository.records.first().single().id)
    }

    @Test fun concurrentStrongCapturesCommitOneRecord() = runBlocking {
        coroutineScope {
            (1..20).map { index ->
                async(Dispatchers.IO) {
                    repository.ingest(payment(capture = "concurrent-$index",
                        source = if (index % 2 == 0) CaptureSource.NOTIFICATION else CaptureSource.ACCESSIBILITY))
                }
            }.awaitAll()
        }
        assertEquals(1, repository.records.first().size)
    }

    @Test fun concurrentIdenticalWeakCaptureCommitsOneReceiptAndRecord() = runBlocking {
        coroutineScope {
            (1..20).map {
                async(Dispatchers.IO) { repository.ingest(payment(transaction = null)) }
            }.awaitAll()
        }
        assertEquals(1, repository.records.first().size)
    }

    @Test fun mergeNeedsUserActionAndTransfersReceiptsAndStrongIdentity() = runBlocking {
        val weak = repository.ingest(payment(transaction = null))
        val strong = repository.ingest(payment(capture = "strong-from-screen", source = CaptureSource.ACCESSIBILITY))
        assertEquals(weak.recordId, repository.getRecord(strong.recordId)?.possibleDuplicateOf)
        repository.mergeDuplicate(strong.recordId)
        assertEquals(1, repository.records.first().size)
        assertEquals(weak.recordId, repository.records.first().single().id)
        assertEquals("SYNTHETIC-ID", repository.records.first().single().transactionId)
        assertEquals(weak.recordId,
            repository.ingest(payment(capture = "strong-from-screen", source = CaptureSource.ACCESSIBILITY)).recordId)
        assertEquals(weak.recordId, repository.ingest(payment(capture = "new-later-strong")).recordId)
        database.close()
        open()
        assertEquals(1, repository.records.first().size)
        assertEquals(weak.recordId, repository.ingest(payment(capture = "strong-from-screen")).recordId)
    }

    @Test fun invalidEditsAreAtomicAndDoNotDamageOriginalData() = runBlocking {
        val first = repository.addManual(draft())
        val before = requireNotNull(repository.getRecord(first))
        val invalid = listOf(
            draft().copy(amountMinor = -1),
            draft().copy(amountMinor = Long.MAX_VALUE),
            draft().copy(occurredAt = -1),
            draft().copy(occurredAt = now + 86_400_001),
            draft().copy(note = "x".repeat(1001)),
            draft().copy(category = "x".repeat(41)),
        )
        invalid.forEach { value ->
            val error = runCatching { repository.update(first, value) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals(before, repository.getRecord(first))
        }
    }

    @Test fun missingAmountsAndOtherFundKindsNeverBecomeConfirmedAutomatically() = runBlocking {
        repository.ingest(payment(capture = "unknown", transaction = null).copy(amountMinor = null, status = RecordStatus.REVIEW))
        repository.ingest(payment(capture = "refund", transaction = "SYNTHETIC-REFUND").copy(kind = EntryKind.REFUND))
        val manual = repository.addManual(draft().copy(kind = EntryKind.TOP_UP))
        repository.confirm(manual, draft().copy(kind = EntryKind.TOP_UP))
        assertEquals(3, repository.records.first().size)
        assertTrue(repository.records.first().all { it.status == RecordStatus.REVIEW.name })
    }

    @Test fun conflictingAmountsWithSameTransactionRequireReview() = runBlocking {
        val first = repository.ingest(payment())
        repository.ingest(payment(capture = "conflicting-amount").copy(amountMinor = 9999))
        repository.ingest(payment(capture = "matching-original-again"))
        val record = requireNotNull(repository.getRecord(first.recordId))
        assertEquals(1, repository.records.first().size)
        assertEquals(2350L, record.amountMinor)
        assertEquals(RecordStatus.REVIEW.name, record.status)
        assertFalse(record.reviewReason.isNullOrBlank())
    }

    private fun payment(
        capture: String = "notification-1",
        transaction: String? = "SYNTHETIC-ID",
        source: CaptureSource = CaptureSource.NOTIFICATION,
    ) = ParsedPayment(
        amountMinor = 2350, channel = PaymentChannel.WECHAT, merchant = "虚构测试商户", platform = "美团",
        occurredAt = now, timeSource = TimeSource.PAYMENT, kind = EntryKind.EXPENSE,
        status = RecordStatus.CONFIRMED, transactionId = transaction, captureKey = capture,
        contentFingerprint = "synthetic-fingerprint", source = source,
    )

    private fun draft() = LedgerDraft(amountMinor = 2350, occurredAt = now,
        merchant = "虚构测试商户", platform = "美团")
}
