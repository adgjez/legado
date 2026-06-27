package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.AiGeneratedAudio

@Dao
interface AiGeneratedAudioDao {
    @Query("select * from ai_generated_audios order by createdAt desc")
    fun all(): List<AiGeneratedAudio>

    @Query("select * from ai_generated_audios where id = :id")
    fun get(id: String): AiGeneratedAudio?

    @Query("select * from ai_generated_audios where favorite = 1 order by updatedAt desc")
    fun favorites(): List<AiGeneratedAudio>

    @Query("select * from ai_generated_audios where bookKey = :bookKey order by createdAt desc")
    fun byBook(bookKey: String): List<AiGeneratedAudio>

    @Query("select * from ai_generated_audios where audioType = :type order by createdAt desc")
    fun byType(type: String): List<AiGeneratedAudio>

    @Query("select * from ai_generated_audios where name like :keyword or prompt like :keyword")
    fun search(keyword: String): List<AiGeneratedAudio>

    @Query("select * from ai_generated_audios where favorite = 0 and createdAt < :cutoff")
    fun expiredTemporary(cutoff: Long): List<AiGeneratedAudio>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(audio: AiGeneratedAudio)

    @Query("update ai_generated_audios set favorite = :favorite, groupId = :groupId, updatedAt = :updatedAt where id = :id")
    fun setFavorite(id: String, favorite: Boolean, groupId: String?, updatedAt: Long)

    @Query("update ai_generated_audios set lastAccessTime = :time where id = :id")
    fun touchAccess(id: String, time: Long)

    @Query("delete from ai_generated_audios where id = :id")
    fun delete(id: String)
}
