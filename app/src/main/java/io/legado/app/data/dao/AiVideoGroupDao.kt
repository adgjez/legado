package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiVideoGroup

@Dao
interface AiVideoGroupDao {

    @Query("select * from ai_video_groups order by sortOrder asc")
    fun all(): List<AiVideoGroup>

    @Query("select * from ai_video_groups where id = :id")
    fun get(id: String): AiVideoGroup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(group: AiVideoGroup)

    @Query("delete from ai_video_groups where id = :id")
    fun delete(id: String)
}
