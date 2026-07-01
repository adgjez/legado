package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * ArcReel 集成 — AI批量处理引擎
 * 并发处理多个AI请求，带进度追踪和错误恢复
 */
object AiBatchProcessor {

    data class BatchConfig(
        val maxConcurrent: Int = 3,
        val retryCount: Int = 2,
        val retryDelayMs: Long = 2000L,
        val modelConfig: AiModelConfig? = null
    )

    data class BatchProgress(
        val total: Int,
        val completed: Int,
        val failed: Int,
        val currentIndex: Int = 0,
        val currentLabel: String = "",
        val phase: String = ""
    )

    data class BatchResult<T>(
        val results: List<T>,
        val errors: List<BatchError>,
        val totalTimeMs: Long
    )

    data class BatchError(
        val index: Int,
        val label: String,
        val message: String
    )

    private val _progress = MutableStateFlow(BatchProgress(0, 0, 0))
    val progress: StateFlow<BatchProgress> = _progress

    /**
     * 批量处理章节摘要
     */
    suspend fun batchSummarizeChapters(
        inputs: List<AiChapterSummaryService.SummaryInput>,
        forceRefresh: Boolean = false,
        config: BatchConfig = BatchConfig()
    ): BatchResult<AiChapterSummaryService.SummaryResult> {
        val total = inputs.size
        val results = mutableListOf<AiChapterSummaryService.SummaryResult>()
        val errors = mutableListOf<BatchError>()
        val startTime = System.currentTimeMillis()

        inputs.forEachIndexed { index, input ->
            _progress.value = BatchProgress(
                total = total,
                completed = index,
                failed = errors.size,
                currentIndex = index,
                currentLabel = "第${input.chapterIndex + 1}章",
                phase = "摘要"
            )

            try {
                val result = AiChapterSummaryService.summarize(
                    input = input,
                    forceRefresh = forceRefresh,
                    onPartial = {},
                    onStatus = {}
                )
                results.add(AiChapterSummaryService.SummaryResult(
                    chapterIndex = input.chapterIndex,
                    summary = result.summary,
                    cached = result.cacheKey
                ))
            } catch (e: Exception) {
                errors.add(BatchError(
                    index = index,
                    label = "第${input.chapterIndex + 1}章",
                    message = e.message ?: "未知错误"
                ))
            }
        }

        _progress.value = BatchProgress(
            total = total,
            completed = results.size,
            failed = errors.size,
            phase = "完成"
        )

        return BatchResult(
            results = results,
            errors = errors,
            totalTimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 批量处理聊天请求
     */
    suspend fun batchChat(
        prompts: List<String>,
        config: BatchConfig = BatchConfig()
    ): BatchResult<String> {
        val total = prompts.size
        val results = mutableListOf<String>()
        val errors = mutableListOf<BatchError>()
        val startTime = System.currentTimeMillis()
        val semaphore = Semaphore(config.maxConcurrent)

        coroutineScope {
            prompts.mapIndexed { index, prompt ->
                async {
                    semaphore.acquire()
                    try {
                        _progress.value = BatchProgress(
                            total = total,
                            completed = results.size,
                            failed = errors.size,
                            currentIndex = index,
                            currentLabel = "请求 ${index + 1}",
                            phase = "对话"
                        )
                        val result = withRetry(config.retryCount, config.retryDelayMs) {
                            AiChatService.chatStream(
                                messages = listOf(
                                    AiChatMessage(
                                        role = AiChatMessage.Role.USER,
                                        content = prompt
                                    )
                                ),
                                onPartial = {},
                                includeStructuredBlocks = false,
                                useAllTools = false,
                                modelConfigOverride = config.modelConfig
                            )
                        }
                        synchronized(results) { results.add(result) }
                    } catch (e: Exception) {
                        synchronized(errors) {
                            errors.add(BatchError(index, "请求 ${index + 1}", e.message ?: "未知错误"))
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }

        _progress.value = BatchProgress(
            total = total,
            completed = results.size,
            failed = errors.size,
            phase = "完成"
        )

        return BatchResult(
            results = results,
            errors = errors,
            totalTimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 批量生成结构化JSON
     */
    suspend fun <T> batchStructured(
        items: List<T>,
        batchSize: Int = 10,
        config: BatchConfig = BatchConfig(),
        processor: suspend (List<T>) -> String
    ): BatchResult<String> {
        val total = items.size
        val results = mutableListOf<String>()
        val errors = mutableListOf<BatchError>()
        val startTime = System.currentTimeMillis()
        val batches = items.chunked(batchSize)

        batches.forEachIndexed { batchIndex, batch ->
            _progress.value = BatchProgress(
                total = total,
                completed = results.size,
                failed = errors.size,
                currentIndex = batchIndex * batchSize,
                currentLabel = "批次 ${batchIndex + 1}/${batches.size}",
                phase = "结构化处理"
            )

            try {
                val result = withRetry(config.retryCount, config.retryDelayMs) {
                    processor(batch)
                }
                results.add(result)
            } catch (e: Exception) {
                errors.add(BatchError(
                    index = batchIndex,
                    label = "批次 ${batchIndex + 1}",
                    message = e.message ?: "未知错误"
                ))
            }
        }

        _progress.value = BatchProgress(
            total = total,
            completed = results.size,
            failed = errors.size,
            phase = "完成"
        )

        return BatchResult(
            results = results,
            errors = errors,
            totalTimeMs = System.currentTimeMillis() - startTime
        )
    }

    private suspend fun <T> withRetry(
        maxRetries: Int,
        delayMs: Long,
        block: suspend () -> T
    ): T {
        require(maxRetries >= 0) { "retryCount must be >= 0" }
        var lastError: Throwable? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    delay(delayMs * (attempt + 1))
                }
            }
        }
        throw lastError!!
    }

    private class Semaphore(private val maxPermits: Int) {
        private var permits = maxPermits
        private val lock = Any()

        suspend fun acquire() {
            while (true) {
                synchronized(lock) {
                    if (permits > 0) {
                        permits--
                        return
                    }
                }
                delay(200)
            }
        }

        fun release() {
            synchronized(lock) {
                if (permits < maxPermits) permits++
            }
        }
    }
}

/**
 * 扩展 AiChapterSummaryService 以支持 BatchProcessor
 */
fun AiChapterSummaryService.SummaryInput.toSummaryResult(): AiChapterSummaryService.SummaryResult {
    return AiChapterSummaryService.SummaryResult(
        chapterIndex = chapterIndex,
        summary = "",
        cached = ""
    )
}