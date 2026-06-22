# AI 视频生成（P1）— 设计文档

日期：2026-06-22
阶段：P1 / 4
目标：镜像现有 AI 图像 (`AiImageService`/`AiImageGalleryManager`/`AiImageProviderConfig`/`AiImageGalleryActivity`) 的整套架构，做一套 AI 视频生成 + Provider + 画廊 + 前台调度。

## 1. 目标与非目标

**目标**
- 让用户配置一个或多个 AI 视频 Provider（OpenAI/通用 sora 兼容 / JS 脚本），输入文本或图片提示词生成视频
- 生成过程异步、调度统一、结果可看可下可分享
- 复用现有的 OkHttp + Cronet 网络、AI Provider 偏好设置、LiveEventBus 事件、AppConfig、Foreground Service 通知风格
- 复用现有 `VideoPlayerActivity` 和 `GSYVideoPlayer` / `Media3 ExoPlayer` 做播放

**非目标（移到 P2/P3/P4）**
- AI 聊天里调用生成（→ P2）
- 视频摘要/字幕/理解（→ P3）
- 视频播放增强（超分/插帧/AI 配音 → P4）

## 2. 总体架构

```
┌──────────────────────────────────────────────────────────┐
│ UI Layer                                                 │
│  AiVideoGalleryActivity ── 视频画廊（与图像镜像）       │
│  AiVideoProviderManageActivity / EditActivity            │
│  AiVideoPreviewDialog (内嵌 ExoPlayer 预览)             │
└──────────────────────────────────────────────────────────┘
                          │
┌────────────────────────▼─────────────────────────────────┐
│ Service Layer                                            │
│  AiVideoTaskService  (前台服务，统一调度)                │
│   ├─ AiVideoTaskManager (任务队列/状态机/轮询)           │
│   └─ AiVideoGalleryManager (本地文件落盘/数据库)         │
│  AiVideoService.generate()  (高层 API)                   │
└──────────────────────────────────────────────────────────┘
                          │
┌────────────────────────▼─────────────────────────────────┐
│ Provider Adapter Layer (统一接口)                        │
│  AiVideoProvider (interface)                             │
│   ├─ AiVideoOpenAiProvider  (chat.completions+sora 模型) │
│   ├─ AiVideoKlingProvider   (可灵 style)                │
│   └─ AiVideoJsProvider      (Rhino 跑用户脚本)           │
│  AiVideoApi (通用 HTTP 适配 + 轮询工具)                  │
└──────────────────────────────────────────────────────────┘
                          │
┌────────────────────────▼─────────────────────────────────┐
│ Data Layer                                               │
│  AiGeneratedVideo   (Room 实体)                          │
│  AiVideoGroup       (Room 实体)                          │
│  AiVideoProviderConfig (data class, JSON 序列化)         │
│  DAO + Migration_98_99                                   │
└──────────────────────────────────────────────────────────┘
```

## 3. 数据层

### 3.1 新增表（`app/schemas/.../99.json`）

| 表 | 关键字段 |
|---|---|
| `ai_generated_videos` | `id` (uuid), `name`, `prompt`, `negativePrompt`, `providerId`, `providerName`, `model`, `localPath` (mp4 落盘路径), `remoteUrl` (源 URL，可能过期), `coverPath` (本地缩略图), `durationMs`, `width`, `height`, `sizeBytes`, `aspectRatio`, `seed`, `bookKey`, `bookName`, `bookAuthor`, `chapterKey`, `chapterIndex`, `chapterTitle`, `characterId`, `characterName`, `sourceType`, `sourceText`, `status` (pending/running/success/failed/cancelled), `failReason`, `progress` (0-100), `externalTaskId`, `metadataJson`, `favorite`, `groupId`, `createdAt`, `updatedAt`, `completedAt` |
| `ai_video_groups` | `id` (uuid, default = "default"), `name`, `order`, `cover` |
| 索引 | `groupId / favorite / createdAt / bookKey / chapterKey / characterId / status` |

### 3.2 实体与 DAO
- 文件：`app/src/main/java/io/legado/app/data/entities/AiGeneratedVideo.kt`
- 文件：`app/src/main/java/io/legado/app/data/entities/AiVideoGroup.kt`
- 文件：`app/src/main/java/io/legado/app/data/dao/AiGeneratedVideoDao.kt`
- 文件：`app/src/main/java/io/legado/app/data/dao/AiVideoGroupDao.kt`
- 字段命名与 `AiGeneratedImage` 对齐，迁移改写为 `Migration_98_99`

### 3.3 Provider 配置
在 `ui/main/ai/AiConfigModels.kt` 新增 `AiVideoProviderConfig`：

```kotlin
@Keep
data class AiVideoProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String = TYPE_OPENAI,        // openai | kling | js ...
    val baseUrl: String = "",
    val apiKey: String = "",
    val headers: String = "",
    val model: String = "",                // sora-1.0, kling-v1, ...
    val defaultParamsJson: String = "",    // 提示词额外参数 (size/duration/...)
    val stylePrompt: String = "",          // 提示词前缀
    val jsLib: String = "",
    val loginUrl: String = "",
    val loginUi: String = "",
    val enabledCookieJar: Boolean = false,
    val script: String = "",               // JS Provider 脚本
    val pollIntervalMs: Long = 5_000L,     // 轮询间隔
    val maxWaitMs: Long = 1_800_000L,      // 最长等待 (30min)
    val timeoutMillisecond: Long = 120_000L,
    val order: Int = 0,
    val enabled: Boolean = true
) {
    fun displayName(): String = name.ifBlank { type }
    fun validTimeout(): Long = timeoutMillisecond.coerceIn(60_000L, 600_000L)

    companion object {
        const val TYPE_OPENAI = "openai"
        const val TYPE_KLING = "kling"
        const val TYPE_JS = "js"
    }
}
```

`AppConfig`：
- `aiVideoProviders: List<AiVideoProviderConfig>`（与 `aiImageProviders` 镜像）
- `aiCurrentVideoProviderId: String?`
- `findEnabledVideoProvider(id): AiVideoProviderConfig?`
- `defaultEnabledVideoProvider(): AiVideoProviderConfig?`

## 4. Service 层

### 4.1 `AiVideoApi`（通用 HTTP + 轮询）

文件：`app/src/main/java/io/legado/app/help/ai/AiVideoApi.kt`

封装 `okHttpClient` + 重试 + JSON 工具，提供：
- `suspend fun postJson(url, payload, headers, timeoutMs): String`
- `suspend fun getJson(url, headers, timeoutMs): String`
- `suspend fun downloadToFile(url, target, progress): Long`

`AiVideoApi` 是底层 HTTP，与 Provider 解耦。

### 4.2 `AiVideoProvider`（接口）

```kotlin
interface AiVideoProvider {
    val config: AiVideoProviderConfig

    /** 提交任务，返回外部 task id；抛错表示提交失败 */
    suspend fun submit(prompt: String, params: VideoGenerationParams): String

    /** 查询任务状态；返回 (status, progress, videoUrl?, coverUrl?, failReason?) */
    suspend fun poll(externalTaskId: String): VideoPollResult

    /** 取消任务（可选实现） */
    suspend fun cancel(externalTaskId: String): Boolean = false
}

data class VideoGenerationParams(
    val prompt: String,
    val negativePrompt: String = "",
    val firstFrame: String? = null,    // 图生视频首帧 URL/本地路径
    val lastFrame: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Int = 0,
    val seed: Long = -1,
    val extra: JSONObject = JSONObject()
)

data class VideoPollResult(
    val status: VideoStatus,
    val progress: Int = 0,
    val videoUrl: String? = null,
    val coverUrl: String? = null,
    val failReason: String? = null,
    val raw: JSONObject? = null
)

enum class VideoStatus { PENDING, RUNNING, SUCCESS, FAILED, CANCELLED }
```

实现：
- `AiVideoOpenAiProvider` — OpenAI sora 兼容（`/v1/video/generations` 异步 + `/v1/video/generations/{id}` 轮询）
- `AiVideoKlingProvider` — 可灵风格（提交到 `/v1/videos/text2video` + `/v1/videos/{id}`）
- `AiVideoJsProvider` — Rhino 跑 `submit/poll` 函数；支持 `video.result / video.url / video.cover / video.progress / video.status` 字段

### 4.3 `AiVideoService`（高层 API）

文件：`app/src/main/java/io/legado/app/help/ai/AiVideoService.kt`

镜像 `AiImageService`：
```kotlin
suspend fun submitAndStore(
    prompt: String,
    provider: AiVideoProviderConfig? = null,
    negativePrompt: String = "",
    firstFrame: String? = null,
    metadata: AiVideoGalleryManager.VideoMetadata = AiVideoGalleryManager.VideoMetadata()
): AiGeneratedVideo
```

内部：解析 Provider → 选实现 → submit → 落 `pending` 行 → 立刻返回（生成异步由 `AiVideoTaskService` 接管）→ 通过 `LiveEventBus` 通知 UI。

### 4.4 `AiVideoTaskService`（前台服务）

文件：`app/src/main/java/io/legado/app/service/AiVideoTaskService.kt`

- `foregroundServiceType="dataSync"`
- 通知 channel：`channelIdAiVideo`（在 `App` 注册）
- 通知内容：当前任务数 / 进度 / 摘要
- 并发：默认 2 个并发任务（可在 AppConfig 调）
- 状态机：`pending → running → success/failed/cancelled`
- 任务成功：调用 `AiVideoGalleryManager.saveCompletedVideo()` 下载 mp4 + 抽帧做封面，存数据库
- 任务失败：保留 record + failReason，可重试
- 通过 `LiveEventBus` 广播 `AI_VIDEO_PROGRESS / AI_VIDEO_COMPLETED`
- 与现有 `CacheBookService` 风格一致：bind/coroutine 池

### 4.5 `AiVideoGalleryManager`

文件：`app/src/main/java/io/legado/app/help/ai/AiVideoGalleryManager.kt`

- 落盘目录：`filesDir/ai_videos/{id}.mp4` + `filesDir/ai_videos/{id}_cover.jpg`
- 任务完成时从 poll 拿到的 `videoUrl` 下载，写本地路径，更新 `localPath`，`status=success`，`remoteUrl` 备份
- 30 天未收藏自动清理（与图像一致策略）
- 提供 `cleanupExpired()` / `delete(video)` / `rename` / `setFavorite` / `moveGroup`

### 4.6 错误处理
- submit 失败：立刻落 `failed` 行，reason=具体错误
- poll 超时：`failed` + reason="timeout"
- poll 错误（5xx 重试 3 次，再失败）
- 下载失败：保留 record，重试入口（画廊"重新下载"按钮）

## 5. UI 层

### 5.1 Activity 注册（AndroidManifest）
- `AiVideoGalleryActivity`（launchMode=singleTop）
- `AiVideoProviderManageActivity`（launchMode=standard）
- `AiVideoProviderEditActivity`（launchMode=standard, softInputMode=adjustResize|stateHidden）
- `AiVideoPreviewDialog` 用 DialogFragment 包装（点击缩略图打开）

### 5.2 `AiVideoGalleryActivity`
- 镜像 `AiImageGalleryActivity`：搜索 + 网格 + 批量 + 分组筛选
- 网格 item 显示首帧 + 时长 + prompt 前 2 行 + 状态 badge
- 长按进入批量（重命名/分组/删除/收藏）
- 点击进入预览 → 用 `VideoPlayerActivity` 全屏播放（Intent 传 `localPath` / `remoteUrl`）

### 5.3 `AiVideoProviderManageActivity` / EditActivity
- 镜像 AI 图像 Provider 编辑页：
  - 名称、类型 (openai/kling/js)、baseUrl、apiKey、headers、model、pollInterval、maxWait、timeout、order、enabled
  - 脚本（仅 JS 类型）
  - 测试按钮：调用一次 `submit` + 5s 后 `poll` 显示状态

### 5.4 入口
- 我的页面 (`ui/main/my/MyFragment.kt`) 加 "AI 视频" 项
- 配置页 (`ui/config/AiConfigFragment.kt`) 加子项跳到 Provider 管理
- 视频画廊入口：MyFragment 卡片 + 长按 "AI 视频" 入口

## 6. 配置 / 偏好
- `PreferKey` 加：
  - `aiVideoMaxConcurrent = 2`
  - `aiVideoDefaultDurationSec = 5`
  - `aiVideoDefaultAspect = "16:9"`
  - `aiVideoKeepTempDays = 3`

## 7. AndroidManifest / 资源
- 通知 channel：`channelIdAiVideo`（在 `App.createNotificationChannels()` 注册）
- 字符串：`strings.xml` + `strings-zh.xml`
- 布局：
  - `activity_ai_video_gallery.xml`
  - `activity_ai_video_provider_manage.xml`
  - `activity_ai_video_provider_edit.xml`
  - `item_ai_generated_video.xml`
  - `view_ai_video_player_inline.xml`（画廊 item 用的可播放小窗）
- 主题颜色：复用 `R.color.ai_*` 已有 token，无须新增

## 8. 与现有系统的边界
- **不复用** `AiImageService` / `AiGeneratedImage`，避免双向耦合；P2 才在工具层组合
- 通知 channel 独立于 `channelIdDownload/ReadAloud/Web`
- 不影响 `VideoPlayService` / `VideoPlayerActivity`：画廊打开预览仍走 `VideoPlayerActivity`

## 9. 验收标准
- 配一个 `kling` Provider，提交文本提示词，10 分钟内可在画廊看到 success 行 + 可播放
- 失败 Provider 配置：在画廊里能重试
- 杀掉 App 再启动，pending/running 任务能续上（`AiVideoTaskService` 启动时扫表恢复）
- 没有联网时 submit 抛 `UnknownHostException` 而不是 ANR
- DB 迁移 98→99 不丢数据

## 10. 不在本期
- AI 工具调用（P2）
- 视频摘要/字幕（P3）
- 播放增强（P4）
- 视频转码 / 压缩 / 帧插值

## 11. 风险
- 第三方视频 URL 短期过期 → 落本地后 `remoteUrl` 仅作展示
- 大文件 (50~200MB)：下载到 `filesDir` 不走 cache 目录，下载失败可恢复
- Provider 私有协议差异大 → JS Provider 给用户兜底
