package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiReadAloudRoleCache

@Dao
interface AiReadAloudRoleCacheDao {

    @Query("SELECT * FROM ai_read_aloud_role_caches WHERE cacheKey = :cacheKey LIMIT 1")
    fun get(cacheKey: String): AiReadAloudRoleCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(cache: AiReadAloudRoleCache)

    @Query("DELETE FROM ai_read_aloud_role_caches WHERE bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)
}
