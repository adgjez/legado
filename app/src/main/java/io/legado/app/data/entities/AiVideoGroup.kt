package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 视频分组（与 AI 图像分组镜像）
 */
@Entity(
    tableName = "ai_video_groups",
    indices = [Index(value = ["order"])]
)
data class AiVideoGroup(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "0")
    val order: Int = 0,
    @ColumnInfo(defaultValue = "")
    val cover: String = ""
) {
    companion object {
        const val ID_DEFAULT = "default"
        const val NAME_DEFAULT = "默认分组"
    }
}
