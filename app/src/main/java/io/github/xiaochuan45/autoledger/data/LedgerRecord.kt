package io.github.xiaochuan45.autoledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Minimal ledger fields only. Raw screen/notification text is never stored. */
@Entity(tableName = "ledger_records", indices = [Index(value = ["strongKey"], unique = true)])
data class LedgerRecord(
    @PrimaryKey val id: String,
    val amountMinor: Long?,
    val merchant: String?,
    val platform: String?,
    val channel: String,
    val occurredAt: Long,
    val timeSource: String,
    val kind: String,
    val status: String,
    val category: String = "未分类",
    val note: String = "",
    val transactionId: String? = null,
    val strongKey: String? = null,
    val contentFingerprint: String,
    val sourceTypes: String,
    val reviewReason: String? = null,
    val possibleDuplicateOf: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val userEdited: Boolean = false,
)

/** Kept after deletion so reopening history cannot silently recreate a deleted bill. */
@Entity(tableName = "capture_receipts", indices = [Index(value = ["recordId"])])
data class CaptureReceipt(
    @PrimaryKey val captureKey: String,
    val recordId: String,
    val createdAt: Long,
)
