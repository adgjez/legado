package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiGenVoucher

@Dao
interface AiGenVoucherDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(voucher: AiGenVoucher): Long

    @Query("SELECT * FROM ai_gen_vouchers WHERE taskId = :taskId")
    suspend fun byTask(taskId: Long): AiGenVoucher?

    @Query("SELECT * FROM ai_gen_vouchers ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<AiGenVoucher>

    @Query("SELECT SUM(costActual) FROM ai_gen_vouchers WHERE createdAt > :since AND success = 1")
    suspend fun totalCostSince(since: Long): Double

    @Query("SELECT SUM(costActual) FROM ai_gen_vouchers WHERE modality = :modality AND createdAt > :since AND success = 1")
    suspend fun costByModalitySince(modality: String, since: Long): Double

    @Query("DELETE FROM ai_gen_vouchers WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
