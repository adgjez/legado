package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiPurifiedTextCache

@Dao
interface AiPurifiedTextCacheDao {

    @Query("select * from ai_purified_text_cache where bookKey = :bookKey and chapterIndex = :chapterIndex and intensity = :intensity limit 1")
    fun get(bookKey: String, chapterIndex: Int, intensity: Int): AiPurifiedTextCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(cache: AiPurifiedTextCache)

    @Query("delete from ai_purified_text_cache where bookKey = :bookKey and chapterIndex = :chapterIndex")
    fun deleteByChapter(bookKey: String, chapterIndex: Int)

    @Query("delete from ai_purified_text_cache where bookKey = :bookKey")
    fun deleteByBook(bookKey: String)

    @Query("delete from ai_purified_text_cache where createdAt < :cutoff")
    fun deleteOlderThan(cutoff: Long)
}
