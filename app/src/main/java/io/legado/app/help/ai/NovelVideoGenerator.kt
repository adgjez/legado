package io.legado.app.help.ai

import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.NovelVideoCharacterSheet
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.data.entities.NovelVideoSegment
import io.legado.app.data.entities.NovelVideoSegmentStatus
import io.legado.app.help.ai.AiImageGalleryManager.ImageMetadata
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.utils.postEvent
import kotlinx.coroutines.delay

/**
 * 小说→视频流水线编排器（参考 [AiChapterSummaryService] 的形态）。
 *
 * 8 阶段流水线（详见 spec §4）：
 * 1. 加载章节正文 → [NovelVideoChapterLoader]
 * 2. LLM 生成剧本草稿 → [NovelVideoPromptBuilder] + [AiChatService] + [NovelVideoScreenplayParser]
 * 3. 剧本审阅（可选）→ postEvent(NOVEL_VIDEO_REVIEW_READY) + 挂起等待用户确认
 * 4. 提取角色 + 三视图 → [NovelVideoPromptBuilder.extractMainCharacters] + [AiImageService.generateAndStore]
 * 5. 逐场景生图 → AiImageService（Phase 4 加多图重载后启用）
 * 6. 逐场景生视频 → AiVideoService（Phase 4 启用）
 * 7. MediaMuxer 合并 → VideoMuxer（Phase 5 启用）
 * 8. 产物入库 + 通知 → BookChapter.resourceUrl + postEvent(NOVEL_VIDEO_COMPLETED)
 *
 * **当前实现进度**：Stage 1-4 完整可用；Stage 5-7 留 TODO 等 Phase 4/5；Stage 8 完成。
 */
object NovelVideoGenerator {

    private const val MAX_LLM_RETRY = 3

    // ============================================================
    // 对外入口
    // ============================================================

    /**
     * 启动一个 job：从 [NovelVideoJob] 当前状态推进流水线。
     *
     * 调用前要求：job 已落库（DRAFTING 或 SCREENPLAY_CONFIRMED 状态）。
     * 调用方（前台服务）负责 catch 异常并落库失败状态。
     *
     * @param isCancelled 由调用方提供的取消信号（如 `coroutineJob.isActive`）
     * @param awaitReviewConfirmation Stage 3 审阅后由 UI 调用 [confirmScreenplay] 解除挂起；
     *        若 [NovelVideoParams.enableReview] = false 则直接跳过审阅进 Stage 4
     */
    suspend fun generate(
        jobId: String,
        isCancelled: () -> Boolean = { false }
    ) {
        val job = appDb.novelVideoDao.getJob(jobId)
            ?: throw IllegalStateException("任务不存在：$jobId")
        val params = NovelVideoParams.fromJob(job)
        val book = appDb.bookDao.getBook(job.bookUrl)
            ?: throw IllegalStateException("书籍不存在：${job.bookName}")

        checkCancelled(isCancelled, job.id)
        when (job.status) {
            NovelVideoJobStatus.DRAFTING -> {
                runDraftingStages(job, book, params, isCancelled)
            }
            NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW -> {
                // 已生成草稿，等待用户审阅；这里直接挂起直到 UI 调用 confirmScreenplay
                awaitReviewConfirmation(jobId, isCancelled)
                runGenerationStages(jobId, book, params, isCancelled)
            }
            NovelVideoJobStatus.SCREENPLAY_CONFIRMED -> {
                runGenerationStages(jobId, book, params, isCancelled)
            }
            NovelVideoJobStatus.GENERATING -> {
                // 断点续传：从第一个未完成 segment 恢复
                runGenerationStages(jobId, book, params, isCancelled)
            }
            else -> throw IllegalStateException("任务状态不支持启动：${job.status}")
        }
    }

    // ============================================================
    // Stage 1-3：草稿阶段
    // ============================================================

    private suspend fun runDraftingStages(
        job: NovelVideoJob,
        book: Book,
        params: NovelVideoParams,
        isCancelled: () -> Boolean
    ) {
        // Stage 1: 加载章节正文
        updateJobStatus(job.id, NovelVideoJobStatus.GENERATING, "加载章节正文")
        val chapters = loadChapters(book, job)
        val firstChapter = chapters.first()
        val chapterText = NovelVideoChapterLoader.loadChapterText(book, firstChapter)
        checkCancelled(isCancelled, job.id)

        // Stage 2: LLM 生成剧本草稿
        updateJobStatus(job.id, NovelVideoJobStatus.GENERATING, "生成剧本草稿")
        val draft = generateScreenplayWithRetry(book, firstChapter, chapterText, params, null)
        checkCancelled(isCancelled, job.id)

        // 落库草稿
        val updatedJob = job.copy(
            draftJson = draft.toJson(),
            status = NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW,
            updatedAt = System.currentTimeMillis()
        )
        appDb.novelVideoDao.updateJob(updatedJob)

        if (params.enableReview) {
            // Stage 3: 请求审阅
            postEvent(EventBus.NOVEL_VIDEO_REVIEW_READY, job.id)
            awaitReviewConfirmation(job.id, isCancelled)
        } else {
            // 跳过审阅，直接确认
            confirmScreenplayInternal(job.id, draft)
        }

        runGenerationStages(job.id, book, params, isCancelled)
    }

    /**
     * Stage 2：LLM 生成剧本，最多重试 [MAX_LLM_RETRY] 次。
     * 每次失败后追加"上次返回不完整，请严格输出 JSON"提示。
     */
    private suspend fun generateScreenplayWithRetry(
        book: Book,
        chapter: BookChapter,
        chapterText: String,
        params: NovelVideoParams,
        previousFeedback: String?
    ): ScreenplayDraft {
        var lastError: Throwable? = null
        var feedback = previousFeedback
        repeat(MAX_LLM_RETRY) { attempt ->
            try {
                val systemPrompt = NovelVideoPromptBuilder.buildDramaSystemPrompt(params)
                val userPrompt = NovelVideoPromptBuilder.buildDramaUserPrompt(
                    book, chapter, chapterText, feedback
                )
                val messages = listOf(
                    AiChatMessage(
                        role = AiChatMessage.Role.USER,
                        content = "$systemPrompt\n\n---\n\n$userPrompt"
                    )
                )
                val raw = AiChatService.chatStream(
                    messages = messages,
                    onPartial = {},
                    includeStructuredBlocks = false,
                    modelConfigOverride = resolveLlmModelConfig(params)
                )
                return NovelVideoScreenplayParser.parse(raw)
            } catch (e: Throwable) {
                lastError = e
                feedback = "上次返回无法解析为合法 JSON（错误：${e.message}）。请严格按 JSON Schema 输出，不要包裹 markdown，使用 ASCII 双引号，不要尾随逗号。"
            }
        }
        throw lastError ?: IllegalStateException("剧本生成失败")
    }

    // ============================================================
    // Stage 3：审阅挂起 / 确认
    // ============================================================

    /**
     * 审阅页 UI 调用：用户确认草稿（可携带编辑过的 draft）。
     * 解除 [awaitReviewConfirmation] 的挂起。
     */
    suspend fun confirmScreenplay(jobId: String, editedDraft: ScreenplayDraft? = null) {
        val job = appDb.novelVideoDao.getJob(jobId) ?: return
        val draft = editedDraft ?: job.draftJson?.let { ScreenplayDraft.fromJson(it) } ?: return
        confirmScreenplayInternal(jobId, draft)
        ReviewConfirmationStore.resolve(jobId, confirmed = true)
    }

    /**
     * 审阅页 UI 调用：用户取消任务。
     */
    suspend fun cancelFromReview(jobId: String) {
        appDb.novelVideoDao.updateJobStatus(jobId, NovelVideoJobStatus.CANCELLED, "用户在审阅页取消")
        ReviewConfirmationStore.resolve(jobId, confirmed = false)
    }

    private suspend fun confirmScreenplayInternal(jobId: String, draft: ScreenplayDraft) {
        val screenplay = Screenplay.fromDraft(draft)
        appDb.novelVideoDao.updateJob(
            appDb.novelVideoDao.getJob(jobId)!!.copy(
                screenplayJson = io.legado.app.utils.GSON.toJson(screenplay),
                status = NovelVideoJobStatus.SCREENPLAY_CONFIRMED,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 挂起当前协程直到 UI 调用 [confirmScreenplay] 或 [cancelFromReview]。
     * 每 400ms 检查一次 [isCancelled]，避免服务停止后还卡死。
     */
    private suspend fun awaitReviewConfirmation(jobId: String, isCancelled: () -> Boolean) {
        while (true) {
            checkCancelled(isCancelled, jobId)
            val job = appDb.novelVideoDao.getJob(jobId)
            if (job == null || job.status == NovelVideoJobStatus.CANCELLED) {
                throw IllegalStateException("任务已取消或不存在")
            }
            if (job.status == NovelVideoJobStatus.SCREENPLAY_CONFIRMED) return
            delay(400)
        }
    }

    // ============================================================
    // Stage 4-8：生成阶段
    // ============================================================

    private suspend fun runGenerationStages(
        jobId: String,
        book: Book,
        params: NovelVideoParams,
        isCancelled: () -> Boolean
    ) {
        val job = appDb.novelVideoDao.getJob(jobId)
            ?: throw IllegalStateException("任务不存在：$jobId")
        val screenplayJson = job.screenplayJson
            ?: throw IllegalStateException("剧本未确认，screenplayJson 为空")
        val screenplay = Screenplay.fromJson(screenplayJson)

        // Stage 4: 角色三视图
        updateJobStatus(jobId, NovelVideoJobStatus.GENERATING, "生成角色三视图")
        val characterSheets = ensureCharacterSheets(job, screenplay, params, isCancelled)
        checkCancelled(isCancelled, jobId)

        // Stage 5-7: 逐场景生图 + 生视频 + 合并
        updateJobStatus(jobId, NovelVideoJobStatus.GENERATING, "生成场景视频")
        runScenePipeline(job, book, screenplay, characterSheets, params, isCancelled)

        // Stage 8: 产物入库 + 通知
        finalizeJob(job, book, screenplay, params, isCancelled)
    }

    /**
     * Stage 4：从 screenplay 抽取主要角色，对每个角色生成三视图组合图。
     * 已存在的角色表跳过（断点续传）。
     */
    private suspend fun ensureCharacterSheets(
        job: NovelVideoJob,
        screenplay: Screenplay,
        params: NovelVideoParams,
        isCancelled: () -> Boolean
    ): List<NovelVideoCharacterSheet> {
        val existing = appDb.novelVideoDao.getCharacterSheetsByJob(job.id)
        if (existing.isNotEmpty()) return existing

        val candidates = NovelVideoPromptBuilder.extractMainCharacters(
            screenplay.scenes, params.maxCharacters
        )
        if (candidates.isEmpty()) return emptyList()

        val sheets = mutableListOf<NovelVideoCharacterSheet>()
        candidates.forEachIndexed { idx, candidate ->
            checkCancelled(isCancelled, job.id)
            val sheet = generateCharacterSheet(job, candidate, idx, params)
            sheets.add(sheet)
        }
        if (sheets.isNotEmpty()) {
            appDb.novelVideoDao.insertCharacterSheets(sheets)
        }
        return sheets
    }

    private suspend fun generateCharacterSheet(
        job: NovelVideoJob,
        candidate: CharacterCandidate,
        index: Int,
        params: NovelVideoParams
    ): NovelVideoCharacterSheet {
        val prompt = NovelVideoPromptBuilder.buildCombinedViewPrompt(candidate.description)
        val metadata = ImageMetadata(
            bookName = job.bookName,
            sourceType = "novel_video_character",
            sourceText = candidate.description
        )
        val provider = AiImageService.providerByIdOrNull(params.imageProviderId)
        return runCatching {
            val image = AiImageService.generateAndStore(prompt, provider, metadata)
            NovelVideoCharacterSheet(
                id = "cs_${job.id}_${index}",
                jobId = job.id,
                characterId = "char_${index}",
                characterName = candidate.name,
                description = candidate.description,
                role = candidate.role,
                combinedViewUrl = image.localPath,
                localPath = image.localPath,
                status = NovelVideoCharacterSheetStatus.COMPLETED,
                updatedAt = System.currentTimeMillis()
            )
        }.getOrElse { e ->
            NovelVideoCharacterSheet(
                id = "cs_${job.id}_${index}",
                jobId = job.id,
                characterId = "char_${index}",
                characterName = candidate.name,
                description = candidate.description,
                role = candidate.role,
                status = NovelVideoCharacterSheetStatus.FAILED,
                errorMessage = e.message,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * Stage 5-7：逐场景生图 + 生视频 + 合并。
     *
     * 当前实现：插入 segment 行（PENDING），Stage 5/6/7 留 TODO 等 Phase 4/5。
     */
    private suspend fun runScenePipeline(
        job: NovelVideoJob,
        book: Book,
        screenplay: Screenplay,
        characterSheets: List<NovelVideoCharacterSheet>,
        params: NovelVideoParams,
        isCancelled: () -> Boolean
    ) {
        // 1. 落库 segment 行（断点续传时跳过已存在的）
        ensureSegments(job, screenplay)

        // 2. Stage 5: 逐场景生图（TODO Phase 4：需 AiImageService 多图重载）
        // 3. Stage 6: 逐场景生视频（TODO Phase 4：需 AiVideoService）
        // 4. Stage 7: 合并（TODO Phase 5：需 VideoMuxer）
        // 当前 Phase 3 仅落库 segments，让流水线结构完整可测；
        // Phase 4/5 完成后在此处补齐调用。
        val segments = appDb.novelVideoDao.getSegmentsByJob(job.id)
        if (segments.isNotEmpty() && segments.all { it.status == NovelVideoSegmentStatus.VIDEO_COMPLETED }) {
            // Phase 5 完成后这里会真的合并；当前 stub 直接置 MERGING 等待 Phase 5
            updateJobStatus(job.id, NovelVideoJobStatus.MERGING, "等待合并")
        }
    }

    /**
     * 把 screenplay.scenes 落库为 [NovelVideoSegment] 行（断点续传时跳过已存在的）。
     */
    private suspend fun ensureSegments(job: NovelVideoJob, screenplay: Screenplay) {
        val existing = appDb.novelVideoDao.getSegmentsByJob(job.id)
        val existingSceneIds = existing.map { it.sceneId }.toSet()
        val toInsert = screenplay.scenes.filter { it.sceneId !in existingSceneIds }.map { scene ->
            NovelVideoSegment(
                id = "seg_${job.id}_${scene.sceneId}",
                jobId = job.id,
                chapterIndex = job.chapterStartIndex.takeIf { it >= 0 } ?: 0,
                chapterTitle = job.chapterTitlesJson.let {
                    runCatching {
                        io.legado.app.utils.GSON.fromJson(
                            it,
                            List::class.java
                        ) as? List<String>
                    }.getOrNull()?.firstOrNull().orEmpty()
                },
                sceneId = scene.sceneId,
                narration = scene.narration,
                imagePrompt = scene.imagePrompt,
                videoPrompt = scene.videoPrompt,
                characterDescription = scene.effectiveCharacterDescription,
                status = NovelVideoSegmentStatus.PENDING,
                updatedAt = System.currentTimeMillis()
            )
        }
        if (toInsert.isNotEmpty()) {
            appDb.novelVideoDao.insertSegments(toInsert)
        }
    }

    /**
     * Stage 8：合并产物入库 + 通知。
     *
     * Phase 3 stub：仅更新状态为 COMPLETED，outputPath 等 Phase 5 完成后回填。
     * 若 Phase 4/5 尚未实现（无 segment 完成），标 FAILED 提示待实现。
     */
    private suspend fun finalizeJob(
        job: NovelVideoJob,
        book: Book,
        screenplay: Screenplay,
        params: NovelVideoParams,
        isCancelled: () -> Boolean
    ) {
        checkCancelled(isCancelled, job.id)
        val segments = appDb.novelVideoDao.getSegmentsByJob(job.id)
        val progress = appDb.novelVideoDao.getSegmentProgress(job.id)
        val finalStatus = when {
            progress.completed == 0 && progress.total > 0 -> {
                // Phase 3 checkpoint：Stage 5/6/7 未实现，无 segment 完成
                updateJobStatus(
                    job.id,
                    NovelVideoJobStatus.FAILED,
                    "Stage 5/6/7 待 Phase 4/5 实现（AiVideoService + VideoMuxer）"
                )
                postEvent(EventBus.NOVEL_VIDEO_FAILED, job.id)
                return
            }
            progress.isMajorityFailed -> NovelVideoJobStatus.PARTIAL_FAILED
            else -> NovelVideoJobStatus.COMPLETED
        }
        appDb.novelVideoDao.updateJob(
            job.copy(
                status = finalStatus,
                totalDurationMs = segments.sumOf { it.durationMs ?: 0L },
                updatedAt = System.currentTimeMillis()
            )
        )
        postEvent(EventBus.NOVEL_VIDEO_COMPLETED, job.id)
    }

    // ============================================================
    // 工具
    // ============================================================

    private suspend fun loadChapters(book: Book, job: NovelVideoJob): List<BookChapter> {
        val startIndex = job.chapterStartIndex
        val endIndex = job.chapterEndIndex
        return if (startIndex >= 0 && endIndex >= startIndex) {
            appDb.bookChapterDao.getChapterList(book.bookUrl)
                .filter { it.index in startIndex..endIndex }
        } else if (startIndex >= 0) {
            listOfNotNull(appDb.bookChapterDao.getChapter(book.bookUrl, startIndex))
        } else {
            // 默认取当前章节
            val cur = appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)
            listOfNotNull(cur)
        }
    }

    private fun resolveLlmModelConfig(params: NovelVideoParams) =
        params.llmModelId?.let { id ->
            AppConfig.aiModelConfigList.firstOrNull { it.id == id }
        } ?: AppConfig.aiSummaryModelConfig

    private suspend fun updateJobStatus(jobId: String, status: String, message: String? = null) {
        if (message.isNullOrBlank()) {
            appDb.novelVideoDao.updateJobStatus(jobId, status, System.currentTimeMillis())
        } else {
            appDb.novelVideoDao.updateJobStatusWithError(jobId, status, message, System.currentTimeMillis())
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean, jobId: String) {
        if (isCancelled()) throw kotlinx.coroutines.CancellationException("任务被取消：$jobId")
    }
}

/**
 * Stage 3 审阅挂起/确认的内存信号量。
 *
 * 当前实现用轮询数据库状态（[NovelVideoGenerator.awaitReviewConfirmation]），
 * 这个 store 留作未来切到 deferred 的扩展点。
 */
internal object ReviewConfirmationStore {
    fun resolve(jobId: String, confirmed: Boolean) {
        // 当前依赖 DB 状态轮询，这里空实现；后续可换 Deferred<Job>
    }
}
