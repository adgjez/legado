package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiVideoAnalysis

@Dao
interface AiVideoAnalysisDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(row: AiVideoAnalysis): Long

    @Query("select * from ai_video_analysis where id = :id")
    fun get(id: Long): AiVideoAnalysis?

    /**
     * 按 (bookId, kind, language) 查最新一条。
     * 索引 idx_book_kind_lang 是 unique，可直接定位缓存行。
     */
    @Query(
        "select * from ai_video_analysis " +
            "where bookId = :bookId and kind = :kind and language = :language " +
            "order by updatedAt desc limit 1"
    )
    fun byBookAndKind(bookId: String, kind: String, language: String): AiVideoAnalysis?

    /**
     * 查某 bookId 下的所有分析行。
     */
    @Query("select * from ai_video_analysis where bookId = :bookId order by updatedAt desc")
    fun byBook(bookId: String): List<AiVideoAnalysis>

    /**
     * 查某 bookId + kind 下的所有历史行（用于列出失败重试候选）。
     */
    @Query(
        "select * from ai_video_analysis " +
            "where bookId = :bookId and kind = :kind " +
            "order by updatedAt desc"
    )
    fun allByBookAndKind(bookId: String, kind: String): List<AiVideoAnalysis>

    /**
     * 删除某条（按 id）。
     */
    @Query("delete from ai_video_analysis where id = :id")
    fun delete(id: Long)

    /**
     * 删除某 bookId 的所有分析行（用户清空缓存时调用）。
     */
    @Query("delete from ai_video_analysis where bookId = :bookId")
    fun deleteByBook(bookId: String)

    /**
     * 清理 30 天未更新的 success 行（与 P1 策略一致）。
     */
    @Query(
        "delete from ai_video_analysis " +
            "where status = :success and updatedAt < :before"
    )
    fun cleanupOldSuccess(success: String, before: Long): Int
}
