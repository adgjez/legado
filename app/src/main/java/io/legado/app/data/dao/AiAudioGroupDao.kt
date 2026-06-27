package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.AiAudioGroup

@Dao
interface AiAudioGroupDao {
    @Query("select * from ai_audio_groups order by sortOrder asc")
    fun all(): List<AiAudioGroup>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(group: AiAudioGroup)

    @Query("delete from ai_audio_groups where id = :id")
    fun delete(id: String)
}
