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

    @Query("select * from ai_generated_videos where favorite = 1 order by updatedAt desc")
    fun favorites(): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where favorite = 0 order by createdAt desc")
    fun temporary(): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where groupId = :groupId and favorite = 1 order by updatedAt desc")
    fun byGroup(groupId: String): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where bookKey = :bookKey order by createdAt desc")
    fun byBook(bookKey: String): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where chapterKey = :chapterKey order by createdAt desc")
    fun byChapter(chapterKey: String): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where sourceType = :sourceType order by createdAt desc")
    fun bySourceType(sourceType: String): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where generationMode = :mode order by createdAt desc")
    fun byMode(mode: String): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where parentVideoId = :parentId order by createdAt desc")
    fun byParent(parentId: String): List<AiGeneratedVideo>

    @Query(
        """
        select * from ai_generated_videos
        where name like :keyword
           or prompt like :keyword
           or bookName like :keyword
           or bookAuthor like :keyword
           or chapterTitle like :keyword
        order by createdAt desc
        """
    )
    fun search(keyword: String): List<AiGeneratedVideo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(video: AiGeneratedVideo)

    @Query("update ai_generated_videos set name = :name, updatedAt = :updatedAt where id = :id")
    fun rename(id: String, name: String, updatedAt: Long)

    @Query("update ai_generated_videos set favorite = :favorite, groupId = :groupId, updatedAt = :updatedAt where id = :id")
    fun setFavorite(id: String, favorite: Boolean, groupId: String?, updatedAt: Long)

    @Query("update ai_generated_videos set groupId = :targetGroupId, updatedAt = :updatedAt where groupId = :sourceGroupId and favorite = 1")
    fun moveGroup(sourceGroupId: String, targetGroupId: String, updatedAt: Long)

    @Query("update ai_generated_videos set lastAccessTime = :time where id = :id")
    fun touchAccess(id: String, time: Long)

    @Query("delete from ai_generated_videos where id = :id")
    fun delete(id: String)

    @Query("select * from ai_generated_videos where favorite = 0 and createdAt < :cutoff")
    fun expiredTemporary(cutoff: Long): List<AiGeneratedVideo>

    @Query("select * from ai_generated_videos where favorite = 0 and lastAccessTime < :cutoff order by lastAccessTime asc limit :limit")
    fun lruCandidates(cutoff: Long, limit: Int): List<AiGeneratedVideo>

    @Query("select count(*) from ai_generated_videos")
    fun count(): Int
}
