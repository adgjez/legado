package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiGeneratedVideo

@Dao
interface AiGeneratedVideoDao {

    @Query("select * from ai_generated_videos order by createdAt desc")
    fun all(): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where id = :id")
    fun get(id: String): AiGeneratedVideo?

    @Query("select * from ai_generated_videos where status in (:statuses) order by createdAt desc")
    fun byStatus(statuses: List<String>): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where status = :status order by createdAt desc")
    fun byStatusSingle(status: String): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where favorite = 1 order by updatedAt desc")
    fun favorites(): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where groupId = :groupId and favorite = 1 order by updatedAt desc")
    fun byGroup(groupId: String): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where bookKey = :bookKey order by createdAt desc")
    fun byBook(bookKey: String): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where chapterKey = :chapterKey order by createdAt desc")
    fun byChapter(chapterKey: String): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where sourceType = :sourceType order by createdAt desc")
    fun bySourceType(sourceType: String): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where characterId = :characterId order by createdAt desc")
    fun byCharacter(characterId: Long): List<AiGeneratedVideo>

    @Query(
        """
        select * from ai_generated_videos
        where name like :keyword
           or prompt like :keyword
           or bookName like :keyword
           or bookAuthor like :keyword
           or chapterTitle like :keyword
           or characterName like :keyword
        order by createdAt desc
        """
    )
    fun search(keyword: String): List<AiGeneratedVideo>

    @Query("select count(*) from ai_generated_videos where status = :status")
    fun countByStatus(status: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(video: AiGeneratedVideo)

    @Query("update ai_generated_videos set name = :name, updatedAt = :updatedAt where id = :id")
    fun rename(id: String, name: String, updatedAt: Long)

    @Query(
        "update ai_generated_videos set status = :status, failReason = :failReason, " +
            "progress = :progress, updatedAt = :updatedAt where id = :id"
    )
    fun updateStatus(id: String, status: String, failReason: String, progress: Int, updatedAt: Long)

    @Query("update ai_generated_videos set progress = :progress, updatedAt = :updatedAt where id = :id")
    fun updateProgress(id: String, progress: Int, updatedAt: Long)

    @Query(
        "update ai_generated_videos set localPath = :localPath, remoteUrl = :remoteUrl, " +
            "coverPath = :coverPath, durationMs = :durationMs, width = :width, height = :height, " +
            "sizeBytes = :sizeBytes, aspectRatio = :aspectRatio, status = :status, " +
            "progress = 100, completedAt = :completedAt, updatedAt = :updatedAt where id = :id"
    )
    fun markSuccess(
        id: String,
        localPath: String,
        remoteUrl: String,
        coverPath: String,
        durationMs: Long,
        width: Int,
        height: Int,
        sizeBytes: Long,
        aspectRatio: String,
        status: String,
        completedAt: Long,
        updatedAt: Long
    )

    @Query(
        "update ai_generated_videos set favorite = :favorite, groupId = :groupId, updatedAt = :updatedAt " +
            "where id = :id"
    )
    fun setFavorite(id: String, favorite: Boolean, groupId: String?, updatedAt: Long)

    @Query("update ai_generated_videos set groupId = :targetGroupId, updatedAt = :updatedAt " +
        "where groupId = :sourceGroupId and favorite = 1")
    fun moveGroup(sourceGroupId: String, targetGroupId: String, updatedAt: Long)

    @Query("delete from ai_generated_videos where id = :id")
    fun delete(id: String)

    @Query("delete from ai_generated_videos where status in (:statuses) and createdAt < :before")
    fun deleteOlderThan(statuses: List<String>, before: Long)
}
