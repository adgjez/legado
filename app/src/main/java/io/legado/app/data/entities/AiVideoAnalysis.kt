package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 视频分析结果缓存（P3）。
 *
 * 用途：缓存基于 [bookId]（Book.bookId 或 RSS article id）的 AI 视频分析结果，
 * 包括摘要、字幕、章节、关键帧、封面 5 种 kind。多次请求走缓存避免重复 LLM/ASR 调用。
 *
 * 状态机：pending → running → success / failed / cancelled
 *
 * 字段约定：
 *  - [payloadJson] 存结果 JSON（字幕 SRT 文本 / 章节数组 / 关键帧路径数组 / 封面路径 / 摘要文本）。
 *  - 关键帧/封面图片实际落盘在 `filesDir/ai_video_analysis/{bookId}/` 目录下，路径写进 payloadJson。
 *  - 临时音频落 `filesDir/ai_video_analysis/{bookId}/audio.m4a`。
 */
@Entity(
    tableName = "ai_video_analysis",
    indices = [
        Index(value = ["bookId", "kind", "language"], unique = true, name = "idx_book_kind_lang"),
        Index("bookId", name = "idx_book"),
        Index("status", name = "idx_status"),
        Index("updatedAt", name = "idx_updatedAt")
    ]
)
data class AiVideoAnalysis(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** 关联的 bookId 或 RSS article id。 */
    val bookId: String,
    /** 分析类型：summary / subtitle / chapters / keyframes / cover。 */
    val kind: String,
    /** 字幕语种（仅 kind==subtitle 有意义），其他 kind 可空。 */
    val language: String = "",
    /** 结果 JSON。成功时为结果数据；失败时为空。 */
    val payloadJson: String = "",
    /** 使用的模型名（ASR / LLM / 关键帧模型等）。 */
    val model: String = "",
    /** 关联的 provider id（ASR provider / LLM provider）。 */
    val providerId: String = "",
    /** 任务状态：pending/running/success/failed/cancelled。 */
    val status: String = STATUS_PENDING,
    /** 失败原因。仅 status==failed 有意义。 */
    val failReason: String = "",
    /** 创建时间。 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 更新时间（命中缓存判断 / 清理策略都基于此）。 */
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        const val KIND_SUMMARY = "summary"
        const val KIND_SUBTITLE = "subtitle"
        const val KIND_CHAPTERS = "chapters"
        const val KIND_KEYFRAMES = "keyframes"
        const val KIND_COVER = "cover"
    }
}
