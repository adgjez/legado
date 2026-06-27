package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiStoryScene

@Dao
interface AiStorySceneDao {
    @Query("select * from ai_story_scenes where playlistId = :playlistId order by sceneIndex asc")
    fun byPlaylist(playlistId: String): List<AiStoryScene>

    @Query("select * from ai_story_scenes where id = :id")
    fun get(id: String): AiStoryScene?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(scene: AiStoryScene)

    @Query("update ai_story_scenes set imageId = :imageId, status = :status where id = :id")
    fun updateImage(id: String, imageId: String, status: String)

    @Query("update ai_story_scenes set videoId = :videoId, status = :status where id = :id")
    fun updateVideo(id: String, videoId: String, status: String)

    @Query("update ai_story_scenes set status = :status, error = :error where id = :id")
    fun updateStatus(id: String, status: String, error: String = "")

    @Query("delete from ai_story_scenes where playlistId = :playlistId")
    fun deleteByPlaylist(playlistId: String)
}
