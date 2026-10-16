package io.github.xiaochuan45.autoledger.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import io.github.xiaochuan45.autoledger.core.CaptureSource
import io.github.xiaochuan45.autoledger.core.EntryKind
import io.github.xiaochuan45.autoledger.core.LedgerDraft
import io.github.xiaochuan45.autoledger.core.ParsedPayment
import io.github.xiaochuan45.autoledger.core.RecordStatus
import io.github.xiaochuan45.autoledger.core.TimeSource
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class IngestOutcome { INSERTED, UPDATED, DUPLICATE, SUPPRESSED_DELETED }
data class IngestResult(val outcome: IngestOutcome, val recordId: String)

/** Atomic, conservative capture ingestion; similarity is evidence for review, never for merging. */
class LedgerRepository internal constructor(
    private val database: LedgerDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.ledgerDao()
    val records: Flow<List<LedgerRecord>> = dao.observeRecords()

    suspend fun getRecord(id: String): LedgerRecord? = dao.find(id)

    suspend fun ingest(payment: ParsedPayment): IngestResult = database.withTransaction {
        val now = clock()
        // Rehash even if the parser already hashed these values: no source key reaches disk.
        val receiptKey = hash(payment.captureKey)
        dao.receipt(receiptKey)?.let { receipt ->
            val previous = dao.find(receipt.recordId)
            return@withTransaction IngestResult(
                if (previous?.deletedAt != null) IngestOutcome.SUPPRESSED_DELETED else IngestOutcome.DUPLICATE,
                receipt.recordId,
            )
        }
        // Payment identity must survive a user correcting TRANSFER/TOP_UP/etc. into EXPENSE.
        // A refund's displayed transaction number can refer to the original payment, so this
        // prototype deliberately never treats it as a distinct, reliable refund identifier.
        val transactionId = if (payment.kind == EntryKind.REFUND) null else
            payment.transactionId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
        val strongKey = transactionId?.let { hash("${payment.channel.name}\u0000$it") }
        if (strongKey != null) {
            dao.findStrong(strongKey)?.let { previous ->
                dao.insertReceipt(CaptureReceipt(receiptKey, previous.id, now))
                if (previous.deletedAt != null) {
                    return@withTransaction IngestResult(IngestOutcome.SUPPRESSED_DELETED, previous.id)
                }
                val enriched = enrich(previous, payment, now)
                dao.update(enriched)
                return@withTransaction IngestResult(
                    if (enriched != previous) IngestOutcome.UPDATED else IngestOutcome.DUPLICATE,
                    previous.id,
                )
            }
        }

        val validAmount = payment.amountMinor?.takeIf { it in 0..MAX_AMOUNT_MINOR }
        val validTime = payment.occurredAt in MIN_TIME..(now + FUTURE_TOLERANCE)
        val occurredAt = if (validTime) payment.occurredAt else now
        val possibleDuplicate = if (validAmount != null && validTime) {
            dao.nearby(payment.channel.name, payment.kind.name, validAmount,
                occurredAt - WEAK_WINDOW, occurredAt + WEAK_WINDOW)
                .filter { previous ->
                    // Two known, different transaction IDs are two independent payments.
                    (strongKey == null || previous.strongKey == null) &&
                        normalize(previous.merchant) == normalize(payment.merchant) &&
                        timesCompatible(previous, payment)
                }
                .minWithOrNull(compareBy<LedgerRecord>(
                    { it.deletedAt != null }, { it.status != RecordStatus.CONFIRMED.name },
                    { it.possibleDuplicateOf != null }, { it.createdAt },
                ))
        } else null
        val reviewReason = when {
            validAmount == null -> "金额不明确或超出支持范围，请核对"
            !validTime -> "交易时间无效，已使用识别时间，请核对"
            payment.kind != EntryKind.EXPENSE -> "此版本仅自动确认消费；请核对资金类型，退款尚未关联原消费"
            possibleDuplicate != null -> "与已有记录相似但缺少共同交易号，请核对是否为同一笔"
            payment.status == RecordStatus.REVIEW -> payment.reviewReason ?: "交易状态或信息不明确，请核对"
            else -> null
        }
        val id = UUID.randomUUID().toString()
        dao.insert(LedgerRecord(
            id = id,
            amountMinor = validAmount,
            merchant = clean(payment.merchant, 200),
            platform = clean(payment.platform, 200),
            channel = payment.channel.name,
            occurredAt = occurredAt,
            timeSource = if (validTime) payment.timeSource.name else TimeSource.OBSERVED.name,
            kind = payment.kind.name,
            status = if (reviewReason == null) RecordStatus.CONFIRMED.name else RecordStatus.REVIEW.name,
            transactionId = transactionId,
            strongKey = strongKey,
            contentFingerprint = hash(payment.contentFingerprint),
            sourceTypes = payment.source.name,
            reviewReason = reviewReason,
            possibleDuplicateOf = possibleDuplicate?.id,
            createdAt = now,
            updatedAt = now,
        ))
        dao.insertReceipt(CaptureReceipt(receiptKey, id, now))
        IngestResult(IngestOutcome.INSERTED, id)
    }

    suspend fun addManual(draft: LedgerDraft): String = database.withTransaction {
        val value = validate(draft)
        val now = clock()
        val id = UUID.randomUUID().toString()
        val review = value.kind != EntryKind.EXPENSE
        dao.insert(LedgerRecord(
            id = id, amountMinor = value.amountMinor, merchant = value.merchant, platform = value.platform,
            channel = value.channel.name, occurredAt = value.occurredAt, timeSource = TimeSource.USER.name,
            kind = value.kind.name, status = if (review) RecordStatus.REVIEW.name else RecordStatus.CONFIRMED.name,
            category = value.category, note = value.note,
            contentFingerprint = hash("manual:$id"), sourceTypes = CaptureSource.MANUAL.name,
            reviewReason = if (review) UNSUPPORTED_KIND_REASON else null,
            createdAt = now, updatedAt = now, userEdited = true,
        ))
        id
    }

    suspend fun update(id: String, draft: LedgerDraft) = edit(id, draft, confirm = false)

    suspend fun confirm(id: String, draft: LedgerDraft) = edit(id, draft, confirm = true)

    private suspend fun edit(id: String, draft: LedgerDraft, confirm: Boolean) = database.withTransaction {
        val value = validate(draft)
        val previous = requireNotNull(dao.find(id)) { "账单不存在" }
        require(previous.deletedAt == null) { "请先撤销删除再修改" }
        val status = when {
            value.kind != EntryKind.EXPENSE -> RecordStatus.REVIEW.name
            confirm -> RecordStatus.CONFIRMED.name
            else -> previous.status
        }
        dao.update(previous.copy(
            amountMinor = value.amountMinor, occurredAt = value.occurredAt,
            merchant = value.merchant, platform = value.platform, channel = value.channel.name,
            kind = value.kind.name, category = value.category, note = value.note,
            timeSource = if (value.occurredAt != previous.occurredAt) TimeSource.USER.name else previous.timeSource,
            status = status,
            reviewReason = when {
                value.kind != EntryKind.EXPENSE -> UNSUPPORTED_KIND_REASON
                status == RecordStatus.CONFIRMED.name -> null
                else -> previous.reviewReason
            },
            // A separate confirmed payment no longer offers a misleading merge action.
            possibleDuplicateOf = if (confirm) null else previous.possibleDuplicateOf,
            updatedAt = clock(), userEdited = true,
        ))
    }

    suspend fun delete(id: String) = database.withTransaction {
        dao.find(id)?.let { previous ->
            if (previous.deletedAt == null) dao.update(previous.copy(deletedAt = clock(), updatedAt = clock()))
        }
    }

    suspend fun restore(id: String) = database.withTransaction {
        dao.find(id)?.let { previous ->
            if (previous.deletedAt != null) dao.update(previous.copy(deletedAt = null, updatedAt = clock()))
        }
    }

    /** Only called after an explicit user choice; no similarity rule invokes this method. */
    suspend fun mergeDuplicate(id: String) = database.withTransaction {
        val duplicate = requireNotNull(dao.find(id)) { "账单不存在" }
        require(duplicate.deletedAt == null) { "账单已删除" }
        val targetId = requireNotNull(duplicate.possibleDuplicateOf) { "没有可合并的关联账单" }
        val target = requireNotNull(dao.find(targetId)) { "关联账单不存在" }
        require(target.deletedAt == null) { "关联账单已删除，请先恢复或分别保留" }
        require(duplicate.strongKey == null || target.strongKey == null || duplicate.strongKey == target.strongKey) {
            "两笔交易号不同，不能合并"
        }
        val now = clock()
        // Release the unique key before transferring it to the retained row.
        dao.update(duplicate.copy(deletedAt = now, updatedAt = now, strongKey = null))
        dao.update(target.copy(
            transactionId = target.transactionId ?: duplicate.transactionId,
            strongKey = target.strongKey ?: duplicate.strongKey,
            sourceTypes = unionSources(target.sourceTypes, duplicate.sourceTypes),
            amountMinor = target.amountMinor ?: duplicate.amountMinor,
            merchant = if (target.userEdited) target.merchant else target.merchant ?: duplicate.merchant,
            platform = if (target.userEdited) target.platform else target.platform ?: duplicate.platform,
            updatedAt = now,
        ))
        dao.moveReceipts(duplicate.id, target.id)
    }

    private fun enrich(previous: LedgerRecord, payment: ParsedPayment, now: Long): LedgerRecord {
        val sources = unionSources(previous.sourceTypes, payment.source.name)
        if (previous.userEdited) {
            return if (sources == previous.sourceTypes) previous else previous.copy(sourceTypes = sources, updatedAt = now)
        }
        val amount = previous.amountMinor ?: payment.amountMinor?.takeIf { it in 0..MAX_AMOUNT_MINOR }
        val amountConflict = previous.reviewReason == AMOUNT_CONFLICT_REASON ||
            (previous.amountMinor != null && payment.amountMinor != null && previous.amountMinor != payment.amountMinor)
        val canConfirm = !amountConflict && previous.possibleDuplicateOf == null && amount != null &&
            previous.kind == EntryKind.EXPENSE.name && payment.kind == EntryKind.EXPENSE &&
            payment.status == RecordStatus.CONFIRMED &&
            payment.occurredAt in MIN_TIME..(now + FUTURE_TOLERANCE)
        val paymentTime = previous.timeSource == TimeSource.OBSERVED.name && payment.timeSource == TimeSource.PAYMENT &&
            payment.occurredAt in MIN_TIME..(now + FUTURE_TOLERANCE)
        val value = previous.copy(
            amountMinor = amount,
            merchant = previous.merchant ?: clean(payment.merchant, 200),
            platform = previous.platform ?: clean(payment.platform, 200),
            occurredAt = if (paymentTime) payment.occurredAt else previous.occurredAt,
            timeSource = if (paymentTime) TimeSource.PAYMENT.name else previous.timeSource,
            sourceTypes = sources,
            status = when {
                amountConflict -> RecordStatus.REVIEW.name
                canConfirm -> RecordStatus.CONFIRMED.name
                else -> previous.status
            },
            reviewReason = when {
                amountConflict -> AMOUNT_CONFLICT_REASON
                canConfirm -> null
                else -> previous.reviewReason
            },
        )
        return if (value == previous) previous else value.copy(updatedAt = now)
    }

    private fun timesCompatible(previous: LedgerRecord, payment: ParsedPayment): Boolean {
        val tolerance = if (previous.timeSource == TimeSource.PAYMENT.name && payment.timeSource == TimeSource.PAYMENT) {
            60_000L
        } else WEAK_WINDOW
        return payment.occurredAt in (previous.occurredAt - tolerance)..(previous.occurredAt + tolerance)
    }

    private fun validate(draft: LedgerDraft): LedgerDraft {
        require(draft.amountMinor in 0..MAX_AMOUNT_MINOR) { "金额需在 0 到 99,999,999.99 元之间" }
        require(draft.occurredAt in MIN_TIME..(clock() + FUTURE_TOLERANCE)) { "时间需在 2000 年至当前时间后 24 小时以内" }
        require(draft.note.length <= 1000) { "备注最多 1000 字" }
        require(draft.category.trim().length <= 40) { "分类最多 40 字" }
        require((draft.merchant?.length ?: 0) <= 200) { "商户最多 200 字" }
        require((draft.platform?.length ?: 0) <= 200) { "平台最多 200 字" }
        return draft.copy(
            merchant = clean(draft.merchant, 200), platform = clean(draft.platform, 200),
            category = draft.category.trim().ifEmpty { "未分类" },
        )
    }

    companion object {
        const val MAX_AMOUNT_MINOR = 9_999_999_999L
        private const val MIN_TIME = 946_684_800_000L // 2000-01-01 UTC
        private const val FUTURE_TOLERANCE = 86_400_000L
        private const val WEAK_WINDOW = 120_000L
        private const val UNSUPPORTED_KIND_REASON = "此版本仅确认消费；其他资金类型保留待核对，退款尚未关联原消费"
        private const val AMOUNT_CONFLICT_REASON = "同一交易号出现不同金额，保留原金额，请核对"
        @Volatile private var instance: LedgerRepository? = null

        fun get(context: Context): LedgerRepository = instance ?: synchronized(this) {
            instance ?: LedgerRepository(Room.databaseBuilder(
                context.applicationContext, LedgerDatabase::class.java, "ledger.db",
            ).build()).also { instance = it }
        }

        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun clean(value: String?, limit: Int): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.take(limit)
        private fun normalize(value: String?): String? = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        private fun unionSources(first: String, second: String): String = (first.split(',') + second.split(','))
            .filter { it.isNotBlank() }.distinct().sorted().joinToString(",")
    }
}
