package io.legado.app.help.ai

import android.util.Base64
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.NovelVideoCharacterSheet
import io.legado.app.data.entities.NovelVideoCharacterSheetStatus
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.data.entities.NovelVideoSegment
import io.legado.app.data.entities.NovelVideoSegmentStatus
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.help.ai.AiImageGalleryManager.ImageMetadata
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import splitties.init.appCtx
import java.io.File

/**
 * 小说→视频流水线编排器（参考 [AiChapterSummaryService] 的形态）。
 *
 * 8 阶段流水线（详见 spec §4）：
 * 1. 加载章节正文 → [NovelVideoChapterLoader]
 * 2. LLM 生成剧本草稿 → [NovelVideoPromptBuilder] + [AiChatService] + [NovelVideoScreenplayParser]
 * 3. 剧本审阅（可选）→ postEvent(NOVEL_VIDEO_REVIEW_READY) + 挂起等待用户确认
 * 4. 提取角色 + 三视图 → [NovelVideoPromptBuilder.extractMainCharacters] + [AiImageService.generateAndStore]
 * 5. 逐场景生图 → [AiImageService.generateAndStore]（多图参考重载，传入角色三视图）
 * 6. 逐场景生视频 → [AiVideoTaskPoller.generate]（参考图 = 场景图 + 角色三视图）
 * 7. MediaMuxer 合并 → [VideoMuxer.merge]（无损 remux，时间戳重基准）
 * 8. 产物入库 + 通知 → BookChapter.resourceUrl + postEvent(NOVEL_VIDEO_COMPLETED)
 *
 * **当前实现进度**：Stage 1-7 完整可用；Stage 8 完成。
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
            NovelVideoJobStatus.MERGING -> {
                // 断点续传：Stage 7 合并中断（如进程崩溃），重走 Stage 4-8
                // 已 VIDEO_COMPLETED 的 segment 会被跳过，mergeCompletedSegments 重新合并
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
        // 保持 DRAFTING 状态：若改为 GENERATING，崩溃后重启会进入 runGenerationStages
        // 并因 screenplayJson 为 null 抛异常，导致任务不可恢复
        updateJobStatus(job.id, NovelVideoJobStatus.DRAFTING, "加载章节正文")
        val chapters = loadChapters(book, job)
        if (chapters.isEmpty()) {
            throw IllegalStateException("未找到章节正文：${book.name}（章节范围 ${job.chapterStartIndex}..${job.chapterEndIndex}）")
        }
        val firstChapter = chapters.first()
        val chapterText = NovelVideoChapterLoader.loadChapterText(book, firstChapter)
        checkCancelled(isCancelled, job.id)

        // Stage 2: LLM 生成剧本草稿
        updateJobStatus(job.id, NovelVideoJobStatus.DRAFTING, "生成剧本草稿")
        val draft = generateScreenplayWithRetry(book, firstChapter, chapterText, params, null)
        checkCancelled(isCancelled, job.id)

        // 落库草稿：用 DAO 部分更新避免 read-modify-write 竞态
        // （job 是入口快照，全量 updateJob 会覆盖并发写入的 errorMessage 等字段）
        appDb.novelVideoDao.updateJobDraft(
            job.id,
            draft.toJson(),
            NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW,
            System.currentTimeMillis()
        )

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
            } catch (e: CancellationException) {
                throw e
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
     *
     * 用条件更新：若 job 已被并发置为 COMPLETED/FAILED 等终态，不覆写。
     */
    suspend fun cancelFromReview(jobId: String) {
        updateJobFinalStatus(jobId, NovelVideoJobStatus.CANCELLED, "用户在审阅页取消")
        ReviewConfirmationStore.resolve(jobId, confirmed = false)
    }

    private suspend fun confirmScreenplayInternal(jobId: String, draft: ScreenplayDraft) {
        val screenplay = Screenplay.fromDraft(draft)
        // 用 DAO 部分更新避免 read-modify-write 竞态（之前用 !! 在 TOCTOU 窗口期会 NPE）
        appDb.novelVideoDao.updateJobScreenplay(
            jobId,
            io.legado.app.utils.GSON.toJson(screenplay),
            NovelVideoJobStatus.SCREENPLAY_CONFIRMED,
            System.currentTimeMillis()
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
            // 兜底：job 进入其他终态（FAILED/COMPLETED 等）时不应继续等待
            if (job.status in NovelVideoJobStatus.FINISHED_STATES) {
                throw IllegalStateException("任务已进入终态：${job.status}")
            }
            // 仅在 PENDING_REVIEW 状态下等待；其他中间态也视为异常
            if (job.status != NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW) {
                throw IllegalStateException("任务状态异常，无法继续等待审阅：${job.status}")
            }
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
        // screenplayJson 损坏时降级为 FAILED，避免断点续传时反复抛异常卡死
        val screenplay = try {
            Screenplay.fromJson(screenplayJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            updateJobFinalStatus(jobId, NovelVideoJobStatus.FAILED, "剧本数据损坏：${e.message}")
            postEvent(EventBus.NOVEL_VIDEO_FAILED, jobId)
            return
        }

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
        // 已有足够 COMPLETED 角色表时直接复用（断点续传）
        val completed = existing.filter { it.isCompleted }
        if (completed.size >= params.maxCharacters) {
            return completed.take(params.maxCharacters)
        }

        val candidates = NovelVideoPromptBuilder.extractMainCharacters(
            screenplay.scenes, params.maxCharacters
        )
        if (candidates.isEmpty()) return completed

        // 逐角色生成：COMPLETED 的保留，FAILED/不存在的重新生成
        val sheets = mutableListOf<NovelVideoCharacterSheet>()
        candidates.forEachIndexed { idx, candidate ->
            val charId = "char_${idx}"
            val existingSheet = existing.firstOrNull { it.characterId == charId }
            if (existingSheet?.isCompleted == true) {
                sheets.add(existingSheet)
            } else {
                checkCancelled(isCancelled, job.id)
                sheets.add(generateCharacterSheet(job, candidate, idx, params))
            }
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
        val prompt = NovelVideoPromptBuilder.sanitizeImagePrompt(
            NovelVideoPromptBuilder.buildCombinedViewPrompt(candidate.description)
        )
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
            // 协程取消必须向上传播，不能当作角色表失败处理
            if (e is CancellationException) throw e
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
     * Stage 5: 对每个 segment 调用 [AiImageService.generateAndStore] 的多图参考重载，
     *          参考图 = 已完成的角色三视图（最多 2 张），用于保持人物一致性。
     * Stage 6: 对每个 segment 调用 [AiVideoTaskPoller.generate]，
     *          参考图 = 场景图 + 角色三视图（最多 3 张，场景图优先）。
     * Stage 7: 合并 → TODO Phase 5（VideoMuxer）。
     *
     * 并发：按 [NovelVideoParams.concurrency] 分批，批内并行、批间串行。
     * 断点续传：跳过已 VIDEO_COMPLETED 的 segment；IMAGE_COMPLETED 的跳过生图直接进 Stage 6。
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

        val allSegments = appDb.novelVideoDao.getSegmentsByJob(job.id)
        // 仅处理未完成且未失败的 segment（FAILED 段需用户显式重试）
        val pendingSegments = allSegments.filter {
            it.status != NovelVideoSegmentStatus.VIDEO_COMPLETED &&
                it.status != NovelVideoSegmentStatus.FAILED
        }
        if (pendingSegments.isEmpty()) {
            // 全部已完成或失败，断点续传场景下仍需走 Stage 7 合并
            // （之前直接 return 会跳过 mergeCompletedSegments，导致 outputPath 永不设置）
            updateJobStatus(job.id, NovelVideoJobStatus.MERGING, "等待合并")
            mergeCompletedSegments(job, isCancelled)
            return
        }

        // 预解析角色三视图引用（API 可用的 URL/data URL）
        val characterRefs = characterSheets
            .filter { it.isCompleted }
            .mapNotNull { resolveImageRef(it.combinedViewUrl) }
            .take(2)  // 最多 2 张角色参考图

        val imageProvider = AiImageService.providerByIdOrNull(params.imageProviderId)
        val videoProvider = AiVideoService.providerByIdOrNull(params.videoProviderId)

        // 2. 分批并发处理
        // concurrency 已在 NovelVideoParams.coerced() 中 coerceIn(1, 4)，此处无需重复
        val concurrency = params.concurrency
        pendingSegments.chunked(concurrency).forEach { batch ->
            checkCancelled(isCancelled, job.id)
            coroutineScope {
                batch.map { segment ->
                    async {
                        runCatching {
                            processSegment(job, segment, characterRefs, params, imageProvider, videoProvider, isCancelled)
                        }.onFailure { e ->
                            // 协程取消必须向上传播，不能当作 segment 失败处理
                            if (e is CancellationException) throw e
                            AppLog.put("Segment 处理异常 ${segment.id}", e)
                            // DB 写入和事件发射用 try 包裹，避免二次异常传播到 awaitAll 中断整批
                            try {
                                appDb.novelVideoDao.markSegmentFailed(
                                    segment.id,
                                    NovelVideoSegmentStatus.FAILED,
                                    e.message,
                                    System.currentTimeMillis()
                                )
                                postEvent(EventBus.NOVEL_VIDEO_SEGMENT_UPDATED, segment.id)
                            } catch (dbErr: Throwable) {
                                AppLog.put("markSegmentFailed/postEvent 二次异常 ${segment.id}", dbErr)
                            }
                        }
                    }
                }.awaitAll()
            }
            // 每批结束后发一次进度事件
            postEvent(EventBus.NOVEL_VIDEO_PROGRESS, job.id)
        }

        // 3. Stage 7: MediaMuxer 合并已完成的段
        mergeCompletedSegments(job, isCancelled)
    }

    /**
     * Stage 7：把所有 VIDEO_COMPLETED 的 segment 合并为单个 MP4。
     * 合并结果写入 job.outputPath；失败时 outputPath 留空，不影响 job 完成态。
     */
    private suspend fun mergeCompletedSegments(
        job: NovelVideoJob,
        isCancelled: () -> Boolean
    ) {
        checkCancelled(isCancelled, job.id)
        updateJobStatus(job.id, NovelVideoJobStatus.MERGING, "合并视频段")

        val completedSegs = appDb.novelVideoDao.getSegmentsByStatus(job.id, NovelVideoSegmentStatus.VIDEO_COMPLETED)
        if (completedSegs.isEmpty()) {
            AppLog.put("VideoMuxer 跳过：无已完成的 segment，jobId=${job.id}")
            return
        }

        val inputPaths = completedSegs
            .sortedBy { it.sceneId }
            .mapNotNull { it.localVideoPath?.takeIf { p -> File(p).isFile } }
        if (inputPaths.size < completedSegs.size) {
            // 部分段文件缺失：合并可用段，但记录警告便于排查
            AppLog.put("警告：${completedSegs.size - inputPaths.size} 段视频文件缺失，仅合并 ${inputPaths.size} 段，jobId=${job.id}")
        }
        if (inputPaths.isEmpty()) {
            // segment 标记 VIDEO_COMPLETED 但本地 mp4 缺失：不应静默跳过，
            // 否则 finalizeJob 会因 completed>0 误判 COMPLETED，得到无视频可播的"已完成"任务
            AppLog.put("VideoMuxer 失败：${completedSegs.size} 段标记完成但本地视频文件缺失，jobId=${job.id}")
            updateJobFinalStatus(job.id, NovelVideoJobStatus.FAILED, "已完成段的视频文件缺失")
            postEvent(EventBus.NOVEL_VIDEO_FAILED, job.id)
            return
        }

        val outputPath = File(appCtx.filesDir, "novel_video/${job.id}/merged.mp4").absolutePath
        val result = VideoMuxer.merge(inputPaths, outputPath)
        when (result) {
            is VideoMuxer.MergeResult.Success -> {
                // 用 DAO 部分更新避免 job.copy 把陈旧 status 写回（之前 status 会被回退为 SCREENPLAY_CONFIRMED）
                appDb.novelVideoDao.updateJobOutput(
                    job.id,
                    result.outputPath,
                    null,
                    result.totalDurationMs,
                    NovelVideoJobStatus.MERGING,
                    System.currentTimeMillis()
                )
                AppLog.put("VideoMuxer 合并成功：${result.segmentCount} 段 → ${result.outputPath}")
            }
            is VideoMuxer.MergeResult.Failed -> {
                // 合并失败不阻塞 job 完成，fallback 到首段视频
                AppLog.put("VideoMuxer 合并失败，回退首段视频：${result.message}")
                val fallbackPath = inputPaths.first()
                appDb.novelVideoDao.updateJobOutput(
                    job.id,
                    fallbackPath,
                    null,
                    null,
                    NovelVideoJobStatus.MERGING,
                    System.currentTimeMillis()
                )
            }
        }
    }

    /**
     * 处理单个 segment：Stage 5 生图 → Stage 6 生视频。
     * 失败时更新状态为 FAILED 并返回（不抛异常，避免影响同批其他 segment）。
     *
     * 注意：[segment] 参数是不可变快照，状态更新后通过 [currentStatus] 局部变量跟踪，
     * 避免 Stage 5 完成后因 `segment.status` 仍是旧值而跳过 Stage 6。
     */
    private suspend fun processSegment(
        job: NovelVideoJob,
        segment: NovelVideoSegment,
        characterRefs: List<String>,
        params: NovelVideoParams,
        imageProvider: io.legado.app.ui.main.ai.AiImageProviderConfig?,
        videoProvider: AiVideoProviderConfig?,
        isCancelled: () -> Boolean
    ) {
        var imageUrl = segment.imageUrl
        var currentStatus = segment.status

        // ========== Stage 5: 逐场景生图 ==========
        if (currentStatus == NovelVideoSegmentStatus.PENDING ||
            currentStatus == NovelVideoSegmentStatus.IMAGE_GENERATING
        ) {
            checkCancelled(isCancelled, job.id)
            appDb.novelVideoDao.updateSegmentStatus(
                segment.id, NovelVideoSegmentStatus.IMAGE_GENERATING, null, System.currentTimeMillis()
            )
            val imagePrompt = NovelVideoPromptBuilder.buildSceneImagePrompt(segment, params.stylePrompt)
            val metadata = ImageMetadata(
                bookName = job.bookName,
                chapterIndex = segment.chapterIndex,
                chapterTitle = segment.chapterTitle,
                sourceType = "novel_video_scene",
                sourceText = segment.narration
            )
            // Stage 5 重试 3 次：前 2 次用 sanitizeImagePrompt，第 3 次调 LLM 改写
            val maxRetry = 3
            var savedImage: AiGeneratedImage? = null
            var lastErr: Throwable? = null
            for (attempt in 1..maxRetry) {
                checkCancelled(isCancelled, job.id)
                val sanitizedPrompt = if (attempt < maxRetry) {
                    NovelVideoPromptBuilder.sanitizeImagePrompt(imagePrompt)
                } else {
                    NovelVideoPromptBuilder.rewriteImagePromptForSafety(imagePrompt)
                }
                val result = runCatching {
                    AiImageService.generateAndStore(sanitizedPrompt, characterRefs, imageProvider, metadata)
                }
                if (result.isSuccess) {
                    savedImage = result.getOrThrow()
                    break
                }
                lastErr = result.exceptionOrNull()
                // 协程取消必须向上传播，不进入重试循环
                if (lastErr is CancellationException) throw lastErr
                AppLog.put("场景生图失败 attempt=$attempt ${segment.id}", lastErr)
                if (attempt < maxRetry) delay(1_000L * attempt)
            }
            if (savedImage == null) {
                val err = lastErr?.message
                appDb.novelVideoDao.markSegmentFailed(
                    segment.id, NovelVideoSegmentStatus.FAILED, err, System.currentTimeMillis()
                )
                postEvent(EventBus.NOVEL_VIDEO_SEGMENT_UPDATED, segment.id)
                return
            }
            imageUrl = savedImage.localPath
            currentStatus = NovelVideoSegmentStatus.IMAGE_COMPLETED
            appDb.novelVideoDao.updateSegmentImage(
                segment.id, imageUrl, NovelVideoSegmentStatus.IMAGE_COMPLETED, System.currentTimeMillis()
            )
            postEvent(EventBus.NOVEL_VIDEO_SEGMENT_UPDATED, segment.id)
        }

        // ========== Stage 6: 逐场景生视频 ==========
        if (currentStatus == NovelVideoSegmentStatus.IMAGE_COMPLETED ||
            currentStatus == NovelVideoSegmentStatus.VIDEO_GENERATING
        ) {
            checkCancelled(isCancelled, job.id)
            appDb.novelVideoDao.updateSegmentStatus(
                segment.id, NovelVideoSegmentStatus.VIDEO_GENERATING, null, System.currentTimeMillis()
            )
            val videoPrompt = NovelVideoPromptBuilder.buildSceneVideoPrompt(segment)
            // 参考图：场景图优先 + 角色三视图（最多 3 张）
            val videoRefs = buildList {
                resolveImageRef(imageUrl)?.let { add(it) }
                addAll(characterRefs)
            }.take(3)

            // Stage 6 重试 3 次：前 2 次用 sanitizeVideoPrompt，第 3 次调 LLM 改写
            val maxRetry = 3
            var success: AiVideoTaskPoller.Result.Success? = null
            var failedMsg: String? = null
            for (attempt in 1..maxRetry) {
                checkCancelled(isCancelled, job.id)
                val sanitizedPrompt = if (attempt < maxRetry) {
                    NovelVideoPromptBuilder.sanitizeVideoPrompt(videoPrompt)
                } else {
                    NovelVideoPromptBuilder.rewriteVideoPromptForSafety(videoPrompt)
                }
                val result = AiVideoTaskPoller.generate(
                    prompt = sanitizedPrompt,
                    seconds = params.sceneDurationSeconds,
                    size = params.resolution,
                    referenceImages = videoRefs,
                    jobId = job.id,
                    segId = segment.id,
                    provider = videoProvider,
                    isCancelled = isCancelled
                )
                if (result is AiVideoTaskPoller.Result.Success) {
                    success = result
                    break
                }
                failedMsg = (result as AiVideoTaskPoller.Result.Failed).message
                AppLog.put("场景生视频失败 attempt=$attempt ${segment.id}: $failedMsg", null)
                if (attempt < maxRetry) delay(1_000L * attempt)
            }
            when (success) {
                null -> {
                    appDb.novelVideoDao.markSegmentFailed(
                        segment.id, NovelVideoSegmentStatus.FAILED, failedMsg, System.currentTimeMillis()
                    )
                }
                else -> {
                    appDb.novelVideoDao.updateSegmentVideo(
                        segment.id,
                        success.remoteUrl,
                        success.localPath,
                        params.sceneDurationSeconds * 1000L,
                        NovelVideoSegmentStatus.VIDEO_COMPLETED,
                        System.currentTimeMillis()
                    )
                }
            }
            postEvent(EventBus.NOVEL_VIDEO_SEGMENT_UPDATED, segment.id)
        }
    }

    /**
     * 把 screenplay.scenes 落库为 [NovelVideoSegment] 行（断点续传时跳过已存在的）。
     */
    private suspend fun ensureSegments(job: NovelVideoJob, screenplay: Screenplay) {
        val existing = appDb.novelVideoDao.getSegmentsByJob(job.id)
        val existingSceneIds = existing.map { it.sceneId }.toSet()
        // 对 screenplay.scenes 按 sceneId 去重，防止 LLM 误返回重复 sceneId 时
        // insertSegments 的 REPLACE 策略静默覆写先插入的段
        val uniqueScenes = screenplay.scenes.distinctBy { it.sceneId }
        // chapterTitlesJson 对所有 segment 相同，提到 map 外只解析一次（原 N 次解析）
        val firstChapterTitle = io.legado.app.utils.GSON
            .fromJsonArray<String>(job.chapterTitlesJson)
            .getOrNull()?.firstOrNull().orEmpty()
        val toInsert = uniqueScenes.filter { it.sceneId !in existingSceneIds }.map { scene ->
            NovelVideoSegment(
                id = "seg_${job.id}_${scene.sceneId}",
                jobId = job.id,
                chapterIndex = job.chapterStartIndex.takeIf { it >= 0 } ?: 0,
                chapterTitle = firstChapterTitle,
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
     * Stage 8：产物入库 + 通知。
     *
     * Stage 7 已在 [mergeCompletedSegments] 中完成合并并写入 outputPath。
     * 这里只更新最终状态（COMPLETED/PARTIAL_FAILED/FAILED）。
     * 若 Stage 7 合并失败，outputPath 已 fallback 到首段视频。
     */
    private suspend fun finalizeJob(
        job: NovelVideoJob,
        book: Book,
        screenplay: Screenplay,
        params: NovelVideoParams,
        isCancelled: () -> Boolean
    ) {
        checkCancelled(isCancelled, job.id)
        val progress = appDb.novelVideoDao.getSegmentProgress(job.id)
        // 重新读取 job：Stage 7 已写入 outputPath/totalDurationMs
        val latestJob = appDb.novelVideoDao.getJob(job.id) ?: job
        val finalStatus = when {
            progress.completed == 0 && progress.total > 0 -> {
                // 所有 segment 都失败了
                updateJobFinalStatus(
                    job.id,
                    NovelVideoJobStatus.FAILED,
                    "全部 ${progress.total} 个场景生成失败"
                )
                postEvent(EventBus.NOVEL_VIDEO_FAILED, job.id)
                return
            }
            // outputPath 为空说明 Stage 7 合并未产出可播放文件，不应标记 COMPLETED
            latestJob.outputPath.isNullOrBlank() && progress.completed > 0 -> {
                updateJobFinalStatus(
                    job.id,
                    NovelVideoJobStatus.FAILED,
                    "视频合并未产出文件"
                )
                postEvent(EventBus.NOVEL_VIDEO_FAILED, job.id)
                return
            }
            progress.isMajorityFailed -> NovelVideoJobStatus.PARTIAL_FAILED
            else -> NovelVideoJobStatus.COMPLETED
        }
        // 用条件部分更新避免 TOCTOU 覆写终态：
        // 若期间 markCancelledIfRunning 已把 status 写成 CANCELLED，此处不覆写
        val updated = appDb.novelVideoDao.updateJobFinalStatusIfNotFinished(
            job.id, finalStatus, System.currentTimeMillis()
        )
        if (updated == 0) {
            AppLog.put("jobId=${job.id} 已被并发置为终态，finalizeJob 跳过")
            return
        }
        // 写回 BookChapter.resourceUrl，让阅读页能播放入口
        if (params.attachToBookChapter && finalStatus != NovelVideoJobStatus.FAILED) {
            runCatching { attachOutputToChapter(latestJob, job.chapterStartIndex) }
                .onFailure { AppLog.put("写回 BookChapter.resourceUrl 失败 jobId=${job.id}", it) }
        }
        postEvent(EventBus.NOVEL_VIDEO_COMPLETED, job.id)
    }

    /**
     * 把合并产物写回首章 BookChapter：
     * - resourceUrl = outputPath（视频文件本地路径）
     * - imgUrl = coverPath（视频封面，原为「音频真实URL / 标题段评图」字段，spec 第 18 行复用承载视频产物 URL）
     *
     * 失败不阻塞任务完成，仅记日志。
     */
    private suspend fun attachOutputToChapter(job: NovelVideoJob, chapterIndex: Int) {
        val outputPath = job.outputPath?.takeIf { it.isNotBlank() } ?: return
        val chapter = appDb.bookChapterDao.getChapter(job.bookUrl, chapterIndex) ?: return
        appDb.bookChapterDao.update(
            chapter.copy(
                resourceUrl = outputPath,
                imgUrl = job.coverPath ?: chapter.imgUrl
            )
        )
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

    /**
     * 条件写入终态（COMPLETED/FAILED/CANCELLED/PARTIAL_FAILED）：仅当 job 尚未终结时才更新。
     *
     * 与 [updateJobStatus] 的区别：用 SQL WHERE 子句保证不覆写已存在的终态，
     * 避免 TOCTOU 竞态（如取消已写 CANCELLED 后，失败路径不应再覆写为 FAILED）。
     * @return true 表示成功写入；false 表示已被并发置为终态，调用方应跳过后续动作
     */
    private suspend fun updateJobFinalStatus(
        jobId: String,
        status: String,
        message: String? = null
    ): Boolean {
        val affected = appDb.novelVideoDao.updateJobFinalStatusWithErrorIfNotFinished(
            jobId, status, message, System.currentTimeMillis()
        )
        return affected > 0
    }

    private fun checkCancelled(isCancelled: () -> Boolean, jobId: String) {
        if (isCancelled()) throw kotlinx.coroutines.CancellationException("任务被取消：$jobId")
    }

    /**
     * 把图片引用解析为 API 可用的形式（URL 或 data URL）。
     *
     * - "http(s)://..." 或 "data:" 开头 → 直接返回（API 可直接拉取/内联）
     * - 本地文件路径 → 读取并编码为 `data:image/...;base64,...`
     * - null/空/不存在 → null
     *
     * 用于 Stage 5/6 构建参考图列表：character sheet 的 combinedViewUrl 和 segment 的 imageUrl
     * 都可能存的是 localPath（[AiImageGalleryManager.saveGeneratedImage] 的产物），需要转换。
     */
    private fun resolveImageRef(source: String?): String? {
        val raw = source?.trim().orEmpty()
        if (raw.isBlank()) return null
        // 精确匹配 http:// / https:// / data:，避免 "httpfoo" 等被误判为 URL
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true) || raw.startsWith("data:", true)) return raw
        val file = File(raw)
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            val mime = detectImageMime(bytes)
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:$mime;base64,$base64"
        }.onFailure {
            AppLog.put("图片引用转 data URL 失败: $raw", it)
        }.getOrNull()
    }

    private fun detectImageMime(bytes: ByteArray): String {
        if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return "image/png"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
        // 用 String(bytes, offset, length, charset) 代替 copyOfRange，避免分配临时数组
        if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        ) return "image/webp"
        if (bytes.size >= 3 && String(bytes, 0, 3, Charsets.ISO_8859_1) == "GIF") return "image/gif"
        return "image/png"
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
