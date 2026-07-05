# Novel-to-Video in Legado — 设计规格

- **日期**：2026-07-05
- **分支**：`feature/discovery-suite-select`
- **目标**：在 Legado（阅读 Archive）中复刻 [director_ai](https://github.com/freestylefly/director_ai) 的小说→视频流水线，把书章节文本转成短视频。
- **方案**：混合服务（Approach C）—— 显式流水线编排 + 复用 Legado 已有 AI 基建，仅新增 `AiVideoService` 一个外部依赖。

---

## 1. 决策摘要

| 决策项 | 选择 |
|---|---|
| 视频生成方式 | 外部文生视频 API（veo3.1 风格，跟 director_ai 一致） |
| 输入范围 | 单章 + 章节范围（多章 = N 个单章子任务串联，默认不跨章拼接） |
| 旁白配音 | 不用独立 TTS（音频由视频模型生成，跟 director_ai 一致） |
| 剧本审阅 | 需要审阅步骤（独立 Activity，可编辑/重新生成/确认） |
| 产物归属 | 双轨：写入 `BookChapter.resourceUrl` + 任务中心独立管理 |
| 集成方案 | C 混合服务：固定 8 阶段流水线 + 复用 `AiChatService`/`AiImageService` |

---

## 2. 架构与模块布局

### 2.1 新增包结构（全部在 `app/src/main/java/io/legado/app/`）

```
data/entities/
  NovelVideoJob.kt              ← 任务（bookUrl/chapterRange/status/paramsJson/outputPath）
  NovelVideoSegment.kt          ← 分镜段（jobId/index/narration/imagePrompt/videoPrompt
                                  /characterDescription/imageUrl/videoUrl/status）
  NovelVideoCharacterSheet.kt   ← 角色三视图（jobId/characterId/name/combinedViewUrl）
data/dao/NovelVideoDao.kt       ← 三个实体的 DAO
data/AppDatabase.kt             ← bump version 109 → 110，加 Migration
data/DatabaseMigrations.kt      ← MIGRATION_109_110

help/ai/
  NovelVideoGenerator.kt        ← object，流水线编排（参考 AiChapterSummaryService）
  NovelVideoPromptBuilder.kt    ← 系统提示词 + 提示词净化
  NovelVideoChapterLoader.kt    ← 取章节正文 + 切块
  NovelVideoScreenplayParser.kt ← 4 策略 JSON 提取（移植自 director_ai）
  AiVideoService.kt             ← object，文生视频 API 客户端（仿 AiImageService）
  AiVideoProviderConfig.kt      ← 配置数据类（位于 ui/main/ai/AiConfigModels.kt）
  AiVideoTaskPoller.kt          ← 异步任务轮询

> **命名约定**：流水线编排 object = `NovelVideoGenerator`（help/ai/）；前台服务 class = `NovelVideoService`（service/）。两者不同包不同类型，避免混淆。

help/video/
  VideoMuxer.kt                 ← MediaMuxer 无损合并（移植自 director_ai MainActivity.kt）

service/NovelVideoService.kt    ← BaseService + dataSync，长任务宿主（复刻 CacheBookService）

ui/novelvideo/
  NovelVideoManageActivity.kt   ← 任务中心（Compose）
  NovelVideoManageScreen.kt
  NovelVideoReviewActivity.kt   ← 剧本审阅
  NovelVideoReviewScreen.kt
  NovelVideoConfigDialog.kt     ← 生成参数 BottomSheet
  NovelVideoConfigScreen.kt
  NovelVideoJobDetailScreen.kt  ← 任务详情
  NovelVideoSegmentPreview.kt   ← segment 预览

ui/config/
  AiVideoProviderManageActivity.kt  ← 视频Provider管理（仿 AiImageProviderManageActivity）
  AiVideoProviderEditActivity.kt
  AiVideoProviderEditScreen.kt

constant/
  PreferKey.kt (+novelVideo*)       ← 新增键
  EventBus.kt  (+NOVEL_VIDEO_*)     ← 新增事件
  NotificationId.kt (+NovelVideo)   ← 新增通知ID

assets/defaultData/novelVideoProviders.json  ← 默认 Provider 预设
```

### 2.2 复用清单（直接调用，不重写）

| 需求 | 复用对象 | 路径 |
|---|---|---|
| LLM 分镜 | `AiChatService.chat` | `help/ai/AiChatService.kt` |
| 生图（角色三视图 + 场景图） | `AiImageService.generateAndStore`（**需新增多图引用重载**） | `help/ai/AiImageService.kt` |
| 图片存储/画廊 | `AiImageGalleryManager` + `AiGeneratedImage` | `help/ai/AiImageGalleryManager.kt` |
| 取章节正文 | `WebBook.getContentAwait` / `BookHelp` | `model/webBook/`、`help/book/` |
| 长任务宿主 | `BaseService` + `CacheBookService` 模板 | `base/BaseService.kt`、`service/CacheBookService.kt` |
| AI 保活 | `AiTaskKeepAliveService` | `service/AiTaskKeepAliveService.kt` |
| 协程链 | `Coroutine.async(...).onSuccess{}.onError{}` | `help/coroutine/Coroutine.kt` |
| HTTP | `okHttpClient` + `newCallResponse`/`postJson` | `help/http/` |
| 视频播放 | `VideoPlayerActivity` + `VideoPlay.startPlay` | `ui/video/`、`model/VideoPlay.kt` |
| 设置 UI | `ComposeSettingFragment` + `SettingSpecScreen` | `ui/config/compose/` |
| Provider 配置范式 | `AiImageProviderConfig` + `AiImageProviderEditActivity` | `ui/config/` |
| 通知通道 | `AppConst.channelIdAiTask` | `constant/AppConst.kt` |
| JSONPath 解析 | `analyzeRule/` 现有依赖 | `model/analyzeRule/` |

### 2.3 入口按钮挂载点

1. `BookInfoActivity` 操作区 — 「生成视频」按钮（仅 `BookType.text` 显示）
2. `ReadMenu` 阅读菜单 — 「本章生成视频」（与「缓存本章」并列）
3. `ui/main/my/` 或 `BookshelfFragment` — 「小说视频」任务中心入口

---

## 3. 数据模型

### 3.1 `NovelVideoJob`（表 `novel_video_jobs`）

```kotlin
@Entity(tableName = "novel_video_jobs")
data class NovelVideoJob(
    @PrimaryKey val id: String,              // "nv_<timestamp>_<rand>"
    val bookUrl: String,
    val bookName: String,
    val chapterStartIndex: Int,              // 起始章节（含），-1 表示未指定
    val chapterEndIndex: Int,                // 结束章节（含），-1 表示未指定
    val chapterTitlesJson: String,           // ["第1章 xxx", ...] 范围内章节标题快照
    val status: String,                      // drafting/screenplay_pending_review/
                                              //   screenplay_confirmed/generating/merging/
                                              //   completed/failed/cancelled/paused/partial_failed
    val paramsJson: String,                  // NovelVideoParams 序列化
    val screenplayJson: String?,             // 最终 Screenplay JSON（确认后落库）
    val draftJson: String?,                  // 草稿 JSON（审阅前）
    val outputPath: String?,                 // 合并后 mp4 本地路径
    val coverPath: String?,                  // 封面（取首场景图）
    val totalDurationMs: Long?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val attachToBookChapter: Boolean = true  // 是否写入 BookChapter.resourceUrl
)
```

```kotlin
data class NovelVideoParams(
    val sceneCountPerChapter: Int = 7,
    val sceneDurationSeconds: Int = 5,
    val resolution: String = "1280x720",
    val stylePrompt: String = "",
    val imageProviderId: String? = null,
    val videoProviderId: String? = null,
    val llmModelId: String? = null,
    val maxCharacters: Int = 2,
    val concurrency: Int = 2,
    val enableReview: Boolean = true,
    val pollTimeoutMs: Long = 600_000,
    val pollIntervalMs: Long = 2_000
) : Parcelable
```

### 3.2 `NovelVideoSegment`（表 `novel_video_segments`）

```kotlin
@Entity(
    tableName = "novel_video_segments",
    foreignKeys = [ForeignKey(
        NovelVideoJob::class, parentColumns = ["id"], childColumns = ["jobId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("jobId")]
)
data class NovelVideoSegment(
    @PrimaryKey val id: String,              // "seg_<jobId>_<chapterIdx>_<sceneIdx>"
    val jobId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val sceneId: Int,                        // 章节内场景序号（从1）
    val narration: String,                   // 中文旁白
    val imagePrompt: String,                 // 英文图提示
    val videoPrompt: String,                 // 英文视频提示
    val characterDescription: String,
    val imageUrl: String?,
    val videoUrl: String?,                   // API 返回的临时 URL
    val localVideoPath: String?,             // 下载到本地后的路径
    val durationMs: Long?,
    val status: String,                      // pending/image_generating/image_completed/
                                              //   video_generating/video_completed/failed
    val retryCount: Int = 0,
    val errorMessage: String?,
    val updatedAt: Long
)
```

### 3.3 `NovelVideoCharacterSheet`（表 `novel_video_character_sheets`）

```kotlin
@Entity(
    tableName = "novel_video_character_sheets",
    foreignKeys = [ForeignKey(
        NovelVideoJob::class, parentColumns = ["id"], childColumns = ["jobId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("jobId")]
)
data class NovelVideoCharacterSheet(
    @PrimaryKey val id: String,              // "cs_<jobId>_<characterId>"
    val jobId: String,
    val characterId: String,
    val characterName: String,
    val description: String,
    val role: String,                        // "主角"/"第二主角"
    val combinedViewUrl: String?,
    val localPath: String?,
    val status: String,                      // pending/generating/completed/failed
    val errorMessage: String?,
    val updatedAt: Long
)
```

### 3.4 关系图

```
NovelVideoJob (1) ──< NovelVideoSegment (N)      ← CASCADE
                 ──< NovelVideoCharacterSheet (N) ← CASCADE
                 ──> Book (bookUrl)               ← 软关联，不建外键
                 ──> BookChapter (resourceUrl)    ← 软关联
```

### 3.5 Migration

```kotlin
// DatabaseMigrations.kt
val MIGRATION_109_110 = object : Migration(109, 110) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS novel_video_jobs (
                id TEXT NOT NULL PRIMARY KEY,
                bookUrl TEXT NOT NULL, bookName TEXT NOT NULL,
                chapterStartIndex INTEGER NOT NULL, chapterEndIndex INTEGER NOT NULL,
                chapterTitlesJson TEXT NOT NULL, status TEXT NOT NULL,
                paramsJson TEXT NOT NULL, screenplayJson TEXT, draftJson TEXT,
                outputPath TEXT, coverPath TEXT, totalDurationMs INTEGER,
                errorMessage TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                attachToBookChapter INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_novel_video_jobs_bookUrl ON novel_video_jobs(bookUrl)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS novel_video_segments (
                id TEXT NOT NULL PRIMARY KEY,
                jobId TEXT NOT NULL,
                chapterIndex INTEGER NOT NULL, chapterTitle TEXT NOT NULL,
                sceneId INTEGER NOT NULL,
                narration TEXT NOT NULL, imagePrompt TEXT NOT NULL,
                videoPrompt TEXT NOT NULL, characterDescription TEXT NOT NULL,
                imageUrl TEXT, videoUrl TEXT, localVideoPath TEXT, durationMs INTEGER,
                status TEXT NOT NULL, retryCount INTEGER NOT NULL DEFAULT 0,
                errorMessage TEXT, updatedAt INTEGER NOT NULL,
                FOREIGN KEY(jobId) REFERENCES novel_video_jobs(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_novel_video_segments_jobId ON novel_video_segments(jobId)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS novel_video_character_sheets (
                id TEXT NOT NULL PRIMARY KEY,
                jobId TEXT NOT NULL,
                characterId TEXT NOT NULL, characterName TEXT NOT NULL,
                description TEXT NOT NULL, role TEXT NOT NULL,
                combinedViewUrl TEXT, localPath TEXT,
                status TEXT NOT NULL, errorMessage TEXT, updatedAt INTEGER NOT NULL,
                FOREIGN KEY(jobId) REFERENCES novel_video_jobs(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_novel_video_character_sheets_jobId ON novel_video_character_sheets(jobId)")
    }
}

// AppDatabase.kt
@Database(
    entities = [/* existing */, NovelVideoJob::class, NovelVideoSegment::class, NovelVideoCharacterSheet::class],
    version = 110
)
```

### 3.6 关键设计取舍

- **每章独立生成一组 segments**：多章任务 = N 个单章子任务串联，最后可选拼接。断点续传按 chapter 维度恢复。
- **segment 主键带 chapterIndex+sceneId**：天然排序，方便 UI 展示和续传定位。
- **`screenplayJson` 全量落库**：审阅通过后整个 Screenplay JSON 持久化，App 重启后能完整恢复。
- **`localVideoPath` 与 `videoUrl` 分离**：API 返回 URL 是临时的，必须下载到本地（`appCtx.filesDir/novel_video/<jobId>/seg_<id>.mp4`）。
- **不建 Book 外键**：避免删书连带删视频任务，但 `BookChapter.resourceUrl` 写入是软关联，书删了 URL 失效可接受。
- **`attachToBookChapter` 开关**：默认 true（双轨），用户可在任务中心关闭只保留任务记录。

---

## 4. Pipeline 数据流

完整 8 阶段流水线（单章；多章 = N 次单章 + 末尾可选合并）：

### Stage 1：加载章节正文
- `NovelVideoChapterLoader.loadChapter(bookUrl, chapterIndex)` → `WebBook.getContentAwait` / `BookHelp.getContent`
- 返回纯文本（净化/替换后的最终正文）
- 若章长度 > 12k 字符：`splitByParagraph` 切块（参考 `AiChapterSummaryService`）
- 落库：`job.status = DRAFTING`

### Stage 2：LLM 生成剧本草稿
- `NovelVideoPromptBuilder.buildDramaSystemPrompt(params, bookMeta, chunks)` 构造系统提示词
- 要求严格 JSON 输出：`{ taskId, title, genre, estimatedDurationSeconds, emotionalArc: [...], scenes: [{sceneId, narration, mood, emotionalHook, imagePrompt, videoPrompt, characterDescription}] }`
- `AiChatService.chat(messages)` — 复用，3 次重试
- `NovelVideoScreenplayParser.parse(rawJson)` — 移植自 director_ai，4 策略提取（找 task_id → 找 scenes → 找 script_title → 最后一个完整 JSON）+ 中文冒号修复 + 字段验证
- 落库：`job.draftJson = ...`, `job.status = SCREENPLAY_PENDING_REVIEW`
- 若 `params.enableReview = false` → 直接 Stage 4

### Stage 3：剧本审阅（可选，默认开）
- `postEvent(EventBus.NOVEL_VIDEO_REVIEW_READY, jobId)`
- UI：`NovelVideoReviewActivity` 弹出（Compose）
  - 展示 scenes 列表，可编辑 narration/imagePrompt/videoPrompt/characterDescription
  - 操作：确认 / 重新生成（带 feedback）/ 取消
- 用户确认 → `ScreenplayDraft` 转 `Screenplay`，落库 `screenplayJson`
- `job.status = SCREENPLAY_CONFIRMED`
- 用户取消 → `job.status = CANCELLED`，return

### Stage 4：提取主要角色并生成三视图
- 从 `screenplay.scenes[].characterDescription` 提取最多 `maxCharacters` 个角色（按句号 / 大写字母 / 分号 / " and " 分割，移植 `_extractMainCharacters`）
- 对每个角色：
  - `NovelVideoPromptBuilder.buildCombinedViewPrompt(character)` 输出三视图组合提示词
  - `AiImageService.generateAndStore(prompt, imageProviderId, metadata)` — 复用，自动落盘到 `filesDir/ai_images/`
  - 落库 `NovelVideoCharacterSheet`（`combinedViewUrl = gallery path`）
- 并发度 = 1（角色数量少，串行）
- 失败重试：3 次，仍失败则角色表标记 failed，下游场景降级为纯文本生图（无角色参考）

### Stage 5：逐场景生图（人物一致性）
- 状态机：`pending → image_generating → image_completed → video_generating`
- 并发度 = `params.concurrency`（默认 2），`Semaphore` 控制
- 每场景：
  - 场景 1 且有用户参考图：`generateImage(prompt, referenceImages=[userImg])`
  - 场景 2+：`AiImageService.generateByOpenAi(prompt, referenceImages=[cs1, cs2])` — director_ai 的 Chat 格式多图引用，`gpt-4o-image-vip` 模型
  - 提示词净化：`NovelVideoPromptBuilder.sanitizeImagePrompt()` 移除敏感词
  - 失败重试：3 次，可调用 `rewriteImagePromptForSafety()` 让 LLM 重写
  - 落库 `segment.imageUrl, status = IMAGE_COMPLETED`

> **对 `AiImageService` 的扩展**：新增 `generateByOpenAi(prompt, referenceImages: List<String>)` 重载。现有 `generate()` 只支持单图。这是不破坏现有 API 的小幅扩展。

### Stage 6：逐场景生视频（veo3.1 风格）
- 每场景图片完成后立即排队视频任务（不等待全部图片）
- `AiVideoService.submit(prompt, seconds, size, referenceImages=[cs1, cs2, sceneImg])` → multipart/form-data，最多 3 张参考图 → 返回 taskId
- `AiVideoTaskPoller.poll(taskId, timeoutMs, intervalMs) { isCancelled() }` — 分段等待（5×400ms 检查取消），状态机 `queued/in_progress/completed/failed`
- completed：取 `video_url`，下载到 `localVideoPath`
- 提示词净化：`sanitizeVideoPrompt()` 替换 `lightning/fight/explosion/attack/intense/fierce` → `gentle/soft/calm/peaceful`
- 失败重试：3 次
  - 1-2 次：原 prompt 重试（可能是临时网络）
  - 3 次：`rewriteVideoPromptForSafety()` 让 LLM 改写后重试
  - 仍失败：`segment.status = FAILED`，继续其他场景，最后合并时跳过

### Stage 7：MediaMuxer 无损合并
- 等待所有 `status == VIDEO_COMPLETED` 的 segments
- `VideoMuxer.mergeLossless(inputPaths, outputPath)` — 移植自 director_ai `MainActivity.kt:140-265`
  - `MediaMuxer + MediaExtractor`，1MB buffer，累计时间戳偏移
- 要求：所有片段编码参数一致（同分辨率/码率/profile）
  - 失败回退：用 `MediaCodec` 重新编码统一参数后再合并
- 输出：`filesDir/novel_video/<jobId>/merged_<ts>.mp4`
- 落库 `job.outputPath, totalDurationMs, status = MERGING → COMPLETED`

### Stage 8：产物入库与通知
- 若 `attachToBookChapter`：
  - 取首个 `chapterIndex` 对应的 `BookChapter`，写 `resourceUrl = outputPath`
  - 取首场景图写 `imgUrl`（封面）
  - `appDb.bookChapterDao.update(chapter)`
- 通知栏：跳转 `NovelVideoManageActivity` 或直接 `VideoPlayerActivity`
- `postEvent(EventBus.NOVEL_VIDEO_COMPLETED, jobId)`
- （可选）保存到相册：`MediaStore.Video.Media.EXTERNAL_CONTENT_URI`（受 `novelVideoSaveToGallery` 控制）

### 多章任务策略

- 对范围内每个章节循环 Stage 1-6，每章生成独立的 segments + 角色表（**角色跨章可复用，第二次起跳过 Stage 4**）
- Stage 7 合并：默认「每章独立合并」，章间不拼接（避免时长过长、单点失败）
- 用户可在配置里选「全部场景合并成一部」

### 失败容错

- 场景级失败不阻断整体任务，最终合并跳过 `failed` 段（在 UI 上标红）
- 若 `failed` 段 > 50%，`job` 整体标 `PARTIAL_FAILED`

### 取消传播

- 每阶段入口检查 `job.status == CANCELLED`
- 分段轮询内每 400ms 检查一次
- `CoroutineScope.cancel()` 即时中断

### 断点续传

- App 重启后 `NovelVideoManageActivity` 显示 `status=GENERATING` 的任务
- 用户点「继续」从最后一个未完成 segment 恢复

---

## 5. AiVideoProviderConfig（新增 Provider 类型）

完全仿照 `AiImageProviderConfig`，保持配置 UI 和编辑流程同构。

```kotlin
// ui/main/ai/AiConfigModels.kt 内新增
data class AiVideoProviderConfig(
    val id: String,
    val name: String,
    val type: String = TYPE_OPENAI,           // TYPE_OPENAI | TYPE_JS
    val baseUrl: String,
    val apiKey: String = "",
    val headers: String = "",
    val model: String = "veo3.1-components",
    val defaultParamsJson: String = "",
    val stylePrompt: String = "",
    val submitPath: String = "/v1/videos",
    val pollPathTemplate: String = "/v1/videos/{taskId}",
    val taskIdJsonPath: String = "$.id",
    val statusJsonPath: String = "$.status",
    val videoUrlJsonPath: String = "$.video_url",
    val errorJsonPath: String = "$.error",
    val maxReferenceImages: Int = 3,
    val jsLib: String = "",
    val loginUrl: String = "",
    val loginUi: String = "",
    val enabledCookieJar: Boolean = false,
    val script: String = "",
    val timeoutMillisecond: Long = 600_000,
    val pollIntervalMs: Long = 2_000,
    val order: Int = 0,
    val enabled: Boolean = true
) : Parcelable {
    companion object {
        const val TYPE_OPENAI = "openai"
        const val TYPE_JS = "js"
    }
}
```

### `AiVideoService` 接口

```kotlin
object AiVideoService {
    suspend fun submit(
        prompt: String, seconds: Int, size: String,
        referenceImages: List<String>,
        provider: AiVideoProviderConfig?
    ): String /* taskId */

    suspend fun poll(
        taskId: String, provider: AiVideoProviderConfig?,
        isCancelled: () -> Boolean
    ): VideoPollResult

    suspend fun downloadToLocal(videoUrl: String, jobId: String, segId: String): String
}

sealed class VideoPollResult {
    data class Success(val videoUrl: String) : VideoPollResult()
    data class Failed(val message: String) : VideoPollResult()
}
```

### 默认 Provider 预设（`assets/defaultData/novelVideoProviders.json`）

```json
[
  {
    "id": "preset_ciyuan_veo",
    "name": "词元 veo3.1",
    "type": "openai",
    "baseUrl": "https://ciyuan.today",
    "model": "veo3.1-components",
    "submitPath": "/v1/videos",
    "pollPathTemplate": "/v1/videos/{taskId}",
    "taskIdJsonPath": "$.id",
    "statusJsonPath": "$.status",
    "videoUrlJsonPath": "$.video_url",
    "maxReferenceImages": 3,
    "defaultParamsJson": "{\"seconds\":5,\"size\":\"1280x720\",\"watermark\":false}",
    "timeoutMillisecond": 600000,
    "pollIntervalMs": 2000,
    "order": 0,
    "enabled": true
  }
]
```

通过 `DefaultData.kt` 首次启动导入。

### PreferKey 新增

```kotlin
const val aiVideoProviderList = "aiVideoProviderList"
const val aiCurrentVideoProviderId = "aiCurrentVideoProviderId"
const val novelVideoDefaultParams = "novelVideoDefaultParams"
const val novelVideoAutoAttachToChapter = "novelVideoAutoAttachToChapter"  // 默认 true
const val novelVideoSaveToGallery = "novelVideoSaveToGallery"               // 默认 false
```

### AppConfig 新增字段

```kotlin
var aiVideoProviderList: List<AiVideoProviderConfig>
    get() = appCtx.getPrefString(PreferKey.aiVideoProviderList, "")
                .let { if (it.isBlank()) emptyList() else GSON.fromJson(it) }
    set(value) { appCtx.putPrefString(PreferKey.aiVideoProviderList, GSON.toJson(value)) }

val aiCurrentVideoProvider: AiVideoProviderConfig?
    get() = aiVideoProviderList.firstOrNull { it.id == aiCurrentVideoProviderId }
                ?: aiVideoProviderList.firstOrNull()

var novelVideoAutoAttachToChapter: Boolean
    get() = appCtx.getPrefBoolean(PreferKey.novelVideoAutoAttachToChapter, true)
    set(value) = appCtx.putPrefBoolean(PreferKey.novelVideoAutoAttachToChapter, value)
```

### 设置 UI

- `AiVideoProviderManageActivity`（Compose，仿 `AiImageProviderManageActivity`）— 列表 + 增删改排序
- `AiVideoProviderEditActivity`（Compose，仿 `AiImageProviderEditActivity`）— 表单编辑
- 在 `AiConfigFragment` 的 `buildPageSpec()` 加一个分组「视频 Provider」（位于「图片 Provider」之后）
- 视频参数 JSONPath 字段提供「测试连接」按钮：submit 一个最小请求（`prompt="test"`, `seconds=1`, 无参考图），poll 一次返回 status，用于验证 baseUrl/apiKey/JSONPath 配置正确

### 关键设计取舍

- **不重用 `AiImageProviderConfig`**：视频 API 的 submit/poll 双端点 + JSONPath 解析与图片单次生成差异大
- **TYPE_JS 兜底**：用户若用 ComfyUI 本地工作流可用 JS 脚本完全自定义，与 `HttpTTS` 的 `jsLib` 同机制
- **JSONPath 解析**：用 Legado 已有的 JsoupXpath/JsonPath 依赖（`help/analyzeRule/`），不引入新库

---

## 6. UI 流程与界面

### 6.1 入口与导航图

```
BookInfoActivity（书详情）
   └─ [生成视频] 按钮 ────────────┐
                                  ▼
ReadBookActivity / ReadMenu       │
   └─ [本章生成视频] ─────────────┤
                                  ▼
                            NovelVideoConfigDialog
                            （Compose BottomSheet）
                                  │ 确认
                                  ▼
                            NovelVideoService.start(jobId)
                                  │
                                  ▼
                       ┌──────────┴──────────┐
                       │ enableReview=true?  │
                       └──────────┬──────────┘
                          yes │        │ no
                              ▼        ▼
                  NovelVideoReviewActivity  直接 Stage 4
                  （Compose Activity）
                              │ 确认
                              ▼
                      NovelVideoManageActivity
                      （任务中心，列表 + 进度）
                              │ 点击任务
                              ▼
                      NovelVideoJobDetailScreen
                      （章节×场景网格 + 状态 + 重试）
                              │ 点击 segment
                              ▼
                      NovelVideoSegmentPreview
                      （图+视频预览 + 提示词）
                              │ 任务完成
                              ▼
                      VideoPlayerActivity（复用）
                      （播放合并后的 mp4）
```

### 6.2 `NovelVideoConfigDialog`（生成参数，Compose BottomSheet）

字段：
- 章节范围：单选「当前章节」/「章节范围 [N]–[M] 共 K 章」
- 场景数/章：3-12，默认 7
- 单段时长：默认 5 秒
- 分辨率：默认 1280x720
- 画风前缀：默认 "anime style, manga art, 2D animation"
- 角色上限：1-3，默认 2
- 并发数：1-4，默认 2
- LLM Provider / 图像 Provider / 视频 Provider：下拉选择
- ☑ 生成前审阅剧本（默认勾选）
- ☑ 完成后写入章节（resourceUrl）（默认勾选）
- ☐ 完成后保存到相册（默认不勾）

按钮：[取消] [开始生成]

### 6.3 `NovelVideoReviewActivity`（剧本审阅，Compose Activity）

布局：
- 顶部：标题、类型、预估时长、情绪弧
- 角色卡片区：最多 2 张角色卡（名 + 描述可编辑 + 重新生成三视图 + 预览）
- 场景列表（可折叠）：每个场景卡含旁白/情绪/图提示/视频提示/角色描述，均可编辑
- 底部：[重新生成（带反馈）] [取消] [确认开始]

操作：
- 编辑后本地 `draftJson` 更新
- 「重新生成」调用 Stage 2 带 `userFeedback` 重跑 LLM
- 「重新生成三视图」单独重跑某角色的 Stage 4

### 6.4 `NovelVideoManageActivity`（任务中心，Compose）

布局：
- 顶部 Tab：[全部] [进行中] [已完成] [失败]
- 列表项卡片：书名 + 章范围 + 状态徽章 + 进度条 + 当前操作描述 + 操作按钮（继续/暂停/取消/播放/详情/删除）
- 长按多选：批量删除/重试
- 底部：[+ 新建任务]（打开选书 + 选章界面，复用 `CacheBookActivity` 选书逻辑）

### 6.5 `NovelVideoJobDetailScreen`（任务详情）

- 章节分组 → 每章 N 个 segment 网格（缩略图 + 状态徽章）
- 点击 segment：弹出 `NovelVideoSegmentPreview`（大图 + 视频播放器 + 提示词文本 + 重试按钮）
- 失败 segment 显示错误原因 + 「重试」「编辑提示词后重试」

### 6.6 入口按钮挂载（最小侵入）

`BookInfoActivity`（`ui/book/info/BookInfoActivity.kt`）操作区加按钮，仅当 `book.type` 包含 `BookType.text` 时显示。

`ReadMenu`（`ui/book/read/ReadMenu.kt`）菜单加「本章生成视频」，与「缓存本章」「导出本章」并列。

`ui/main/my/` 或 `BookshelfFragment` 顶部加「小说视频」入口，与「缓存」「下载管理」并列。

### Manifest 注册

```xml
<activity android:name=".ui.novelvideo.NovelVideoManageActivity" />
<activity android:name=".ui.novelvideo.NovelVideoReviewActivity" />
<activity android:name=".ui.config.AiVideoProviderManageActivity" />
<activity android:name=".ui.config.AiVideoProviderEditActivity" />
<service
    android:name=".service.NovelVideoService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

### 关键设计取舍

- **Compose 优先**：所有新 UI 用 Compose（参考 `AiChatScreen.kt`、`DiscoverySuiteHomeScreen.kt`），用 `LegadoMiuixCard` 等组件保持视觉一致
- **审阅页用独立 Activity**：避免在 BottomSheet 里塞复杂编辑表单，支持横屏/大屏
- **任务中心与缓存页同构**：复用列表/进度/操作模式降低学习成本
- **segment 预览复用 ExoPlayer**：用 `help/exoplayer/ExoPlayerHelper.kt`，不引入新播放器
- **入口按钮条件渲染**：只在 `BookType.text` 书显示

---

## 7. 服务与生命周期

### 7.1 前台服务 `service/NovelVideoService.kt`

复刻 `CacheBookService` 模式，`foregroundServiceType="dataSync"`。

```kotlin
class NovelVideoService : BaseService() {
    private val scope = MainScope()
    private val runningJobs = mutableMapOf<String, Job>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start  -> startJob(intent.getStringExtra("jobId")!!, intent.getStringExtra("params")!!)
            IntentAction.pause  -> runningJobs[intent.getStringExtra("jobId")]?.cancel()
            IntentAction.stop   -> { runningJobs.values.forEach { it.cancel() }; stopSelf() }
            IntentAction.resume -> resumeJob(intent.getStringExtra("jobId")!!)
        }
        return START_STICKY
    }

    private fun startJob(jobId: String, paramsJson: String) {
        val jobParams = GSON.fromJson(paramsJson, NovelVideoParams::class.java)
        val coroutineJob = scope.launch {
            try {
                AiTaskKeepAliveService.acquire(this@NovelVideoService)
                NovelVideoGenerator.generate(jobId, jobParams, isCancelled = { !isActive })
                postEvent(EventBus.NOVEL_VIDEO_COMPLETED, jobId)
            } catch (e: CancellationException) {
                appDb.novelVideoDao.updateJobStatus(jobId, "paused")
            } catch (e: Throwable) {
                appDb.novelVideoDao.updateJobStatus(jobId, "failed", e.message)
                postEvent(EventBus.NOVEL_VIDEO_FAILED, jobId to e.message)
            } finally {
                AiTaskKeepAliveService.release()
                runningJobs.remove(jobId)
                if (runningJobs.isEmpty()) stopSelf()
            }
        }
        runningJobs[jobId] = coroutineJob
    }
}
```

> **命名冲突说明**：`help/ai/NovelVideoService.kt`（流水线编排，`object`）与 `service/NovelVideoService.kt`（前台服务，`class`）同名不同包。流水线编排器建议改名为 `NovelVideoGenerator` 以避免混淆。**实施时采用 `NovelVideoGenerator` 作为流水线编排 object 的名称。**

### 7.2 通知与进度

```kotlin
private fun upNotification() {
    scope.launch {
        while (isActive) {
            delay(1000)
            val runningJobs = appDb.novelVideoDao.getRunningJobs()
            val text = runningJobs.joinToString("\n") { job ->
                val progress = appDb.novelVideoDao.getSegmentProgress(job.id)
                "《${job.bookName}》 ${progress.completed}/${progress.total} 场景"
            }
            val notif = NotificationCompat.Builder(this@NovelVideoService, AppConst.channelIdAiTask)
                .setContentTitle("正在生成小说视频")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_play_24dp)
                .setOngoing(true)
                .build()
            startForeground(NotificationId.NovelVideo, notif)
            postEvent(EventBus.NOVEL_VIDEO_PROGRESS, "")
        }
    }
}
```

### 7.3 EventBus 新增

```kotlin
// constant/EventBus.kt
const val NOVEL_VIDEO_PROGRESS     = "novel_video_progress"
const val NOVEL_VIDEO_REVIEW_READY = "novel_video_review_ready"
const val NOVEL_VIDEO_COMPLETED    = "novel_video_completed"
const val NOVEL_VIDEO_FAILED       = "novel_video_failed"
const val NOVEL_VIDEO_SEGMENT_UPDATED = "novel_video_segment_updated"
```

### 7.4 NotificationId 新增

```kotlin
// constant/NotificationId.kt
const val NovelVideo = 0x7f0a0021  // 实施时在 NotificationId.kt 中选一个未使用的整数值
```

### 7.5 取消与暂停

- **取消**：`IntentAction.pause` → `runningJobs[jobId]?.cancel()` → 协程级取消立即传播；状态机内每段轮询每 400ms 检查；DB 状态置 `paused`
- **暂停恢复**：`resumeJob(jobId)` 从 DB 读 job + segments，跳过所有 `status == VIDEO_COMPLETED` 的 segment，从第一个 `pending` 或 `failed` segment 继续
- **App 杀进程**：`START_STICKY` 让系统重启服务；但内存中的 `runningJobs` 丢失，需用户在任务中心手动点「继续」恢复（避免无声重启消耗电量）

### 7.6 并发模型

- **单服务多任务**：服务内 `runningJobs` map 支持并发多 job，但每个 job 内部串行（避免 API 限流）
- **跨 job 资源隔离**：每个 job 独立 `filesDir/novel_video/<jobId>/` 目录
- **全局并发上限**：所有 job 共享 `Semaphore(AppConfig.threadCount)`（与 `CacheBookService` 一致）
- **取消传播**：`CancellationException` 在 `withContext(Dispatchers.IO)` 内自动抛出，资源（HTTP 连接、文件流）在 `finally` 中释放

### 7.7 DAO

```kotlin
@Dao
interface NovelVideoDao {
    @Insert suspend fun insertJob(job: NovelVideoJob)
    @Insert suspend fun insertSegments(segs: List<NovelVideoSegment>)
    @Insert suspend fun insertCharacterSheets(sheets: List<NovelVideoCharacterSheet>)
    @Update suspend fun updateJob(job: NovelVideoJob)
    @Update suspend fun updateSegment(seg: NovelVideoSegment)
    @Query("UPDATE novel_video_jobs SET status=:status, updatedAt=:time WHERE id=:jobId")
    suspend fun updateJobStatus(jobId: String, status: String, time: Long = System.currentTimeMillis())
    @Query("UPDATE novel_video_jobs SET status=:status, errorMessage=:err, updatedAt=:time WHERE id=:jobId")
    suspend fun updateJobStatus(jobId: String, status: String, err: String?, time: Long = System.currentTimeMillis())
    @Query("SELECT * FROM novel_video_jobs WHERE status IN ('generating','merging','paused') ORDER BY updatedAt DESC")
    suspend fun getRunningJobs(): List<NovelVideoJob>
    @Query("SELECT * FROM novel_video_jobs WHERE bookUrl=:bookUrl ORDER BY createdAt DESC")
    suspend fun getByBook(bookUrl: String): List<NovelVideoJob>
    @Query("SELECT COUNT(*) AS total, SUM(CASE WHEN status='video_completed' THEN 1 ELSE 0 END) AS completed FROM novel_video_segments WHERE jobId=:jobId")
    suspend fun getSegmentProgress(jobId: String): SegmentProgress
    @Query("DELETE FROM novel_video_jobs WHERE id=:jobId")
    suspend fun deleteJob(jobId: String)
}

data class SegmentProgress(val total: Int, val completed: Int)
```

---

## 8. 错误处理与内容安全

### 8.1 重试矩阵

| 阶段 | 失败类型 | 重试策略 | 失败终态 |
|---|---|---|---|
| Stage 1 取正文 | 网络失败/无章节 | 3 次重试，指数退避 1s/2s/4s | `job.status=FAILED`，记 `errorMessage` |
| Stage 2 LLM 分镜 | JSON 解析失败 | 3 次重试，每次加「上次返回不完整，请严格输出 JSON」 | `job.status=FAILED` |
| Stage 2 LLM 内容审核拒绝 | 4xx | 1 次重试，调用 `rewriteScreenplayPromptForSafety()` 改写输入 | `job.status=FAILED` |
| Stage 4 角色三视图 | 生图失败 | 3 次重试 | 该角色 `status=FAILED`，下游场景降级为无角色参考生图 |
| Stage 5 场景生图 | 网络/审核 | 3 次重试：1-2 原提示词，3 调用 `rewriteImagePromptForSafety()` | `segment.status=FAILED`，继续其他场景 |
| Stage 6 视频生成 | 网络/审核/超时 | 3 次重试：1-2 原提示词，3 `rewriteVideoPromptForSafety()` | `segment.status=FAILED` |
| Stage 7 合并 | 编码不一致 | 1 次回退：用 `MediaCodec` 统一参数重编码后再合并 | `job.status=FAILED` |
| Stage 7 合并后下载 | 网络失败 | 3 次重试 | `job.status=PARTIAL_FAILED`（保留部分段） |

### 8.2 内容安全（移植 director_ai 的提示词净化）

```kotlin
private val VIDEO_SENSITIVE_WORDS = listOf(
    "lightning", "fight", "explosion", "attack", "intense", "fierce",
    "battle", "weapon", "blood", "kill", "death", "war"
)
private val VIDEO_SAFE_REPLACEMENTS = mapOf(
    "lightning" to "gentle flash", "fight" to "soft interaction",
    "explosion" to "burst of light", "attack" to "approach",
    "intense" to "soft", "fierce" to "calm",
    "battle" to "encounter", "weapon" to "object",
    "blood" to "red mark", "kill" to "defeat", "death" to "stillness", "war" to "gathering"
)

fun sanitizeVideoPrompt(prompt: String): String {
    var sanitized = prompt.lowercase()
    VIDEO_SENSITIVE_WORDS.forEach { w ->
        sanitized = sanitized.replace(w, VIDEO_SAFE_REPLACEMENTS[w]!!)
    }
    return sanitized
}

fun sanitizeImagePrompt(prompt: String): String = sanitizeVideoPrompt(prompt)

suspend fun rewriteVideoPromptForSafety(prompt: String): String {
    val sys = """你是视频提示词安全审查员。把以下英文视频提示词改写为内容安全版本，
        保留核心动作和场景，但替换所有可能触发审核的词汇（暴力/血腥/武器/战争等）。
        只输出改写后的英文提示词，不要解释。"""
    return AiChatService.chat(listOf(
        AiChatMessage("system", sys),
        AiChatMessage("user", prompt)
    ), modelConfigOverride = AppConfig.aiSummaryModelId?.let { /* 用快速模型 */ })
}

suspend fun rewriteImagePromptForSafety(prompt: String): String = rewriteVideoPromptForSafety(prompt)
```

### 8.3 提示词模板

`NovelVideoPromptBuilder.buildDramaSystemPrompt(params, bookMeta, chapterContent)`：
- 系统提示词要求 LLM 输出严格 JSON（task_id/title/genre/estimated_duration_seconds/emotional_arc/scenes[]）
- 每个场景必须包含：scene_id, narration(中文), mood, emotional_hook, image_prompt(英文，必须以 "anime style, manga art, 2D animation" 开头), video_prompt(英文，格式 "Camera Type + Movement + Action"), character_description
- 场景数 = `params.sceneCountPerChapter`
- 注入书名、章节标题、章节正文摘要作为上下文

`buildCombinedViewPrompt(character)`：移植自 `_buildCombinedViewPrompt`，输出三视图组合提示词。

---

## 9. 测试策略

### 9.1 单元测试（`app/src/test/java/io/legado/app/help/ai/`）

1. `NovelVideoScreenplayParserTest`：4 策略 JSON 提取 + 字段验证 + 中文冒号修复
2. `NovelVideoPromptBuilderTest`：提示词净化（敏感词替换）+ 系统提示词模板渲染
3. `AiVideoServiceTest`：mock OkHttp，验证 multipart 请求体格式 + JSONPath 解析 + 轮询状态机
4. `VideoMuxerTest`：用 assets 里的样本 mp4 验证合并（同编码参数 → 无损合并；不同编码 → 回退重编码）
5. `NovelVideoDaoTest`（Room 测试）：CRUD + CASCADE 删除 + Migration 109→110

### 9.2 集成测试（`app/src/androidTest/java/io/legado/app/`）

6. `NovelVideoGeneratorIntegrationTest`：mock LLM/Image/Video Client，验证完整 pipeline 状态机流转
7. `NovelVideoManageActivityTest`：Compose UI 测试，列表渲染 + 状态过滤 + 点击交互

### 9.3 测试样本

- `app/src/test/resources/novel_video/sample_chapter.txt`：测试用章节正文（5000 字）
- `app/src/test/resources/novel_video/sample_screenplay.json`：LLM 返回的样本 JSON（含正常 + 缺字段 + 中文冒号三种）
- `app/src/test/resources/novel_video/sample_*.mp4`：3 段 5s 测试视频（同编码 + 不同编码各一组）

### 9.4 Mock 策略

LLM/图像/视频 API 全部走接口隔离，便于 mock：

```kotlin
interface LlmClient { suspend fun chat(msgs: List<AiChatMessage>): String }
interface ImageClient { suspend fun generate(prompt: String, refs: List<String>): String }
interface VideoClient {
    suspend fun submit(...): String
    suspend fun poll(...): VideoPollResult
}
```

`AiChatService`/`AiImageService`/`AiVideoService` 是这些接口的具体实现，测试时注入 mock 实现。

> **测试隔离说明**：`AiChatService` 当前是 `object`（单例），测试时需通过 `AppConfig` 注入 mock 或在 `NovelVideoGenerator` 内通过接口依赖。这是对现有代码的小幅重构（把 `object` 改为 `class` + 注入），但只在 `NovelVideoGenerator` 范围内做，不全局重构。

---

## 10. 实施顺序（建议 MVP 路径）

1. 加 `NovelVideoJob`/`NovelVideoSegment`/`NovelVideoCharacterSheet` 实体 + DAO + Migration 109→110
2. 写 `NovelVideoGenerator`（help/ai/），先只做「单章 → 分镜 JSON → 落库」，复用 `AiChatService.chat` + `AiChapterSummaryService` 的切块/缓存模式
3. 加 `NovelVideoScreenplayParser` + 单元测试
4. 加 `NovelVideoService`（service/）前台服务 + 通知 + EventBus
5. 加 `NovelVideoManageActivity`（Compose）展示任务列表与状态
6. 在 `BookInfoActivity` 加「生成视频」按钮
7. 加 `AiVideoProviderConfig` + `AiVideoService` + `AiVideoTaskPoller` + 设置 UI
8. 接入 `AiImageService.generateAndStore` 生成分镜图（含多图引用重载扩展）
9. 加 `NovelVideoCharacterSheet` 生成（Stage 4）
10. 加 `VideoMuxer`（移植 MediaMuxer 实现）+ Stage 7 合并
11. 接入 Stage 6 视频生成（依赖步骤 7 的 AiVideoService）
12. 加 `NovelVideoReviewActivity` 剧本审阅 UI
13. 加 `NovelVideoJobDetailScreen` + `NovelVideoSegmentPreview`
14. 接入 `BookChapter.resourceUrl` 写入 + `VideoPlayerActivity` 播放产物
15. 加 `PreferKey.novelVideo*` 设置 + `NovelVideoConfigDialog`
16. 完善错误处理、重试、提示词净化、内容安全
17. 完善测试（单元 + 集成）
18. 文档更新（CHANGELOG、SKILL.md）

---

## 11. 不在范围内（YAGNI）

- ❌ 独立 TTS 旁白（用户明确选择「不用独立 TTS，跟 director_ai 一致」）
- ❌ Agent 工具集成（用户选择方案 C，不走 `AiAgentRuntime.runToolLoop`）
- ❌ 视频合成 BGM 背景音乐（音频由视频模型生成，不额外混合）
- ❌ 跨章节拼接默认开启（默认每章独立合并，跨章拼接作为可选项后续再考虑）
- ❌ 视频字幕烧录（用户未要求）
- ❌ 多角色语音路由（用户未要求，且不用 TTS）
- ❌ WebDav 同步视频任务（用户未要求）
- ❌ 视频剪辑/特效编辑器（用户未要求）

---

## 12. 风险与缓解

| 风险 | 缓解 |
|---|---|
| veo3.1 API 成本高、限流严格 | 默认并发 2、单段 5s、单章 7 场景；用户可在配置降低；任务中心显示估算成本 |
| 中文小说武侠题材易触发内容审核 | 提示词净化 + LLM 重写兜底；用户可在审阅页手动改提示词 |
| MediaMuxer 要求编码参数一致 | 失败回退用 MediaCodec 重编码统一参数 |
| 长任务跨重启状态丢失 | `screenplayJson` 全量落库 + segment 级状态机 + 用户手动恢复 |
| App 杀进程后无声重启耗电 | `START_STICKY` 但 `runningJobs` 内存丢失，需用户手动「继续」 |
| 数据库迁移失败 | Migration 109→110 仅 CREATE TABLE + INDEX，无 ALTER，低风险；fallbackToDestructiveMigrationFrom 兜底 |
| AiImageService 多图引用扩展破坏现有 API | 仅新增重载，不改现有 `generate()` 签名 |

---

**Spec 结束。** 实施时如遇具体技术细节与本文档冲突，以本文档为准并在此处更新。
