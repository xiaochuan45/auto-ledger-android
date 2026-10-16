package io.github.xiaochuan45.autoledger.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_records WHERE deletedAt IS NULL ORDER BY occurredAt DESC, createdAt DESC")
    fun observeRecords(): Flow<List<LedgerRecord>>

    @Query("SELECT * FROM ledger_records ORDER BY occurredAt DESC, createdAt DESC")
    suspend fun allRecords(): List<LedgerRecord>

    @Query("SELECT * FROM ledger_records WHERE id = :id")
    suspend fun find(id: String): LedgerRecord?

    @Query("SELECT * FROM ledger_records WHERE strongKey = :key LIMIT 1")
    suspend fun findStrong(key: String): LedgerRecord?

    @Query("SELECT * FROM capture_receipts WHERE captureKey = :key")
    suspend fun receipt(key: String): CaptureReceipt?

    @Query("SELECT * FROM ledger_records WHERE channel = :channel AND kind = :kind AND amountMinor = :amount AND occurredAt BETWEEN :start AND :end ORDER BY createdAt DESC")
    suspend fun nearby(channel: String, kind: String, amount: Long, start: Long, end: Long): List<LedgerRecord>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: LedgerRecord)

    @Update
    suspend fun update(record: LedgerRecord)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(receipt: CaptureReceipt)

    @Query("UPDATE capture_receipts SET recordId = :targetId WHERE recordId = :fromId")
    suspend fun moveReceipts(fromId: String, targetId: String)
}

// This first schema is exported. Future changes must add explicit, tested migrations.
@Database(entities = [LedgerRecord::class, CaptureReceipt::class], version = 1, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
}
