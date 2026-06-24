package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiStoryPlaylist

@Dao
interface AiStoryPlaylistDao {
    @Query("select * from ai_story_playlists order by createdAt desc")
    fun all(): List<AiStoryPlaylist>

    @Query("select * from ai_story_playlists where id = :id")
    fun get(id: String): AiStoryPlaylist?

    @Query("select * from ai_story_playlists where bookKey = :bookKey order by createdAt desc")
    fun byBook(bookKey: String): List<AiStoryPlaylist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(playlist: AiStoryPlaylist)

    @Query("delete from ai_story_playlists where id = :id")
    fun delete(id: String)
}
