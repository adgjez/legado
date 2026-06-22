# AI 视频生成（P1）— 实现计划

关联设计：[2026-06-22-ai-video-p1-generation-design.md](file:///workspace/docs/superpowers/specs/2026-06-22-ai-video-p1-generation-design.md)
执行方式：按下列步骤顺序执行；每步完成后跑 `./gradlew :app:assembleDebug` 确保编译通过。

## 阶段 0：准备

1. 在 `app/schemas/io.legado.app.data.AppDatabase/` 下复制 `98.json` 为 `99.json`，暂不修改
2. `AppDatabase` 数据库版本从 98 升到 99（`@Database(version = 99, ...)`）
3. 在 `DatabaseMigrations.kt` 加 `Migration_98_99`，先空实现（`if (db.version < 99) return`），后面再补
4. 在 `AppDatabase` 的 `autoMigrations` 数组加 `AutoMigration(from = 98, to = 99)` 占位（如果空表可走纯 SQL 迁移；实际策略见步骤 1）

## 阶段 1：数据层（实体 / DAO / 迁移 / Provider 配置）

1. 新增 `data/entities/AiVideoGroup.kt`，字段：`id` (PK, default "default")、`name`、`order`、`cover`
2. 新增 `data/entities/AiGeneratedVideo.kt`（字段见设计 §3.1），索引：`groupId / favorite / status / createdAt / bookKey / chapterKey / characterId`
3. 新增 `data/dao/AiVideoGroupDao.kt`：提供 `all / get / insert / update / delete`
4. 新增 `data/dao/AiGeneratedVideoDao.kt`：`all / byStatus / byGroup / byBook / search / get / insert / update / updateStatus / updateProgress / delete / deleteOlderThan / moveGroup / setFavorite / countByStatus`
5. 在 `AppDatabase`：
   - `entities` 数组加 `AiGeneratedVideo::class, AiVideoGroup::class`
   - `abstract val aiVideoGroupDao`
   - `abstract val aiGeneratedVideoDao`
6. 把 `AppDatabase` 数据库版本 99 写到常量 `const val DATABASE_VERSION = 99`
7. 更新 `app/schemas/.../99.json`：运行 `./gradlew :app:exportSchemaDebug` 让 Room 自动导出
8. 在 `data/DatabaseMigrations.kt` 加 `Migration_98_99`，写两张新表的 `CREATE TABLE` SQL（参考 [DatabaseMigrations.kt](file:///workspace/app/src/main/java/io/legado/app/data/DatabaseMigrations.kt) 中现有的写法）
9. 在 `ui/main/ai/AiConfigModels.kt` 加 `AiVideoProviderConfig` data class（字段见设计 §3.3）
10. 在 `help/config/AppConfig.kt`：
    - 加 `aiVideoProviders: List<AiVideoProviderConfig>` 持久化键 `aiVideoProviders`（Gson 列表）
    - 加 `aiCurrentVideoProviderId: String?`，键 `aiCurrentVideoProviderId`
    - 加 `findEnabledVideoProvider(id)` / `defaultEnabledVideoProvider()` / `upsertVideoProvider(cfg)` / `deleteVideoProvider(id)`
    - 字段可空时给空集合默认值
11. 在 `constant/PreferKey.kt` 加：`aiVideoMaxConcurrent` / `aiVideoDefaultDurationSec` / `aiVideoDefaultAspect` / `aiVideoKeepTempDays`

**TDD 步骤**：先写 `AiVideoGroupDaoTest` / `AiGeneratedVideoDaoTest`（参考 `androidTest` 现有用法），覆盖 insert/get/update/delete；运行 `./gradlew :app:testDebugUnitTest`

## 阶段 2：Provider 适配层

1. 新增 `help/ai/AiVideoApi.kt`：
   - `suspend fun postJson(url, payload, headers, timeoutMs): String`
   - `suspend fun getJson(url, headers, timeoutMs): String`
   - `suspend fun downloadToFile(url, target, progress: ((Long) -> Unit)?): Long`
   - 复用现有 `okHttpClient` / `newCallResponse` / `addHeaders` 工具
2. 新增 `help/ai/AiVideoProvider.kt`（接口 + `VideoGenerationParams` + `VideoPollResult` + `VideoStatus` 枚举）
3. 新增 `help/ai/AiVideoOpenAiProvider.kt`：
   - submit: POST `{baseUrl}/v1/video/generations` body `{model, prompt, size?, duration?}`，返回 `id`
   - poll: GET `{baseUrl}/v1/video/generations/{id}`，解析 `status / progress / video.url / video.cover / fail_reason`
4. 新增 `help/ai/AiVideoKlingProvider.kt`：
   - submit: POST `{baseUrl}/v1/videos/text2video` body `{model_name, prompt, duration, aspect_ratio}`，返回 `data.task_id`
   - poll: GET `{baseUrl}/v1/videos/{task_id}`，解析 `data.task_status / data.task_result.videos[0].url / data.task_result.cover_url`
5. 新增 `help/ai/AiVideoJsProvider.kt`：
   - submit/poll 都跑 Rhino
   - submit: `bindings["result"] = submit(prompt, params, provider)` → 用户脚本返回 task id
   - poll: `bindings["result"] = poll(taskId, provider)` → 返回 `{status, progress, videoUrl, coverUrl, failReason}`
   - 复用 `RhinoScriptEngine` / `buildScriptBindings`，参照 `AiImageService.generateByJs`
6. 新增 `help/ai/AiVideoProviderFactory.kt`：`fun create(cfg: AiVideoProviderConfig): AiVideoProvider`
7. 单元测试 `AiVideoApiTest`：mock OkHttp 验证 JSON 序列化与下载进度回调

## 阶段 3：Service / Manager 层

1. 在 `constant/EventBus.kt` 加：
   - `AI_VIDEO_SUBMITTED` (String taskId)
   - `AI_VIDEO_PROGRESS` (Pair<String, Int>)
   - `AI_VIDEO_COMPLETED` (String videoId, String taskId)
   - `AI_VIDEO_FAILED` (Pair<String, String>)
2. 在 `App.createNotificationChannels()` 加 `channelIdAiVideo = "ai_video"`
3. 新增 `help/ai/AiVideoGalleryManager.kt`：
   - 落盘目录：`filesDir/ai_videos/`
   - `VideoMetadata` data class（字段同 `AiImageGalleryManager.ImageMetadata`）
   - `suspend fun saveCompletedVideo(task, videoUrl, coverUrl, provider, model, metadata): AiGeneratedVideo`
   - `suspend fun saveSubmittedTask(prompt, negativePrompt, provider, model, externalTaskId, metadata): AiGeneratedVideo`
   - `fun updateStatus(id, status, failReason?)`
   - `fun updateProgress(id, progress)`
   - `suspend fun cleanupExpired()`
   - `fun delete(video)`
   - 抽帧封面：Media3 ExoPlayer 加载本地 mp4 + `Player.Listener.onPlaybackStateChanged` → 抓 `Bitmap`
4. 新增 `service/AiVideoTaskService.kt`：
   - `foregroundServiceType="dataSync"`
   - `onStartCommand` 接收 action：`submit / cancel / pause / resume / serve`
   - 任务队列：单例 `AiVideoTaskQueue`，限制并发（`AppConfig.aiVideoMaxConcurrent`）
   - 状态机：`PENDING → RUNNING → SUCCESS / FAILED / CANCELLED`
   - 提交时调 `AiVideoService.submitAndStore`（拿到 row id）→ 入队 → 后台协程 `submit` Provider → 轮询直到 success/failed/timeout → 调 `AiVideoGalleryManager.saveCompletedVideo` / `updateStatus(failed, reason)` → 发 LiveEventBus
   - `onCreate` 扫表恢复 `pending/running` 任务
   - 通知：聚合进度 + 当前任务名
5. 在 `AndroidManifest.xml` 注册 service：`foregroundServiceType="dataSync"`
6. 新增 `help/ai/AiVideoService.kt`：
   - `suspend fun submitAndStore(prompt, provider, negativePrompt, firstFrame, metadata): AiGeneratedVideo`
   - `suspend fun pollOnce(task): VideoPollResult`
   - `fun cancel(taskId)`
7. 单元测试 `AiVideoServiceTest`：mock Provider 验证状态机

## 阶段 4：UI 层

1. 新建 `res/layout/activity_ai_video_gallery.xml`、`activity_ai_video_provider_manage.xml`、`activity_ai_video_provider_edit.xml`、`item_ai_generated_video.xml`
2. 字符串：`strings.xml` / `values-zh/strings.xml`：
   - `ai_video_gallery`、`ai_video_provider_manage`、`ai_video_provider_edit`、`ai_video_prompt`、`ai_video_negative_prompt`、`ai_video_submit`、`ai_video_status_pending / running / success / failed / cancelled`、`ai_video_duration`、`ai_video_aspect_ratio`、`ai_video_poll_interval`、`ai_video_max_wait`、`ai_video_test`、`ai_video_first_frame`
3. 新增 `ui/main/ai/AiVideoGalleryActivity.kt`：
   - 镜像 `AiImageGalleryActivity`
   - 网格 item 用 Glide 加载 `coverPath`，没有就显示占位（视频角标 + 时长）
   - 点击：构造 Intent 打开 `VideoPlayerActivity`（用 `localPath` 或 `remoteUrl`）
   - 长按进入批量模式
   - 状态筛选（顶部 chip：全部 / 进行中 / 失败 / 成功）
4. 新增 `ui/main/ai/AiVideoPreviewDialog.kt`（DialogFragment）：内嵌 ExoPlayer 播放本地 mp4
5. 新增 `ui/main/ai/AiVideoProviderManageActivity.kt`：
   - 列表 + 添加按钮
   - 复用 `RecyclerAdapter` + `ItemViewHolder`
6. 新增 `ui/main/ai/AiVideoProviderEditActivity.kt`：
   - 表单：name / type (openai/kling/js) / baseUrl / apiKey / headers / model / pollInterval / maxWait / timeout / order / enabled
   - JS 类型时显示 script 多行编辑（跳转 `CodeEditActivity`）
   - "测试" 按钮：调 Provider.submit，5s 后 poll 一次显示状态
7. 在 `AndroidManifest.xml` 注册上面 3 个 Activity
8. 入口：
   - `ui/main/my/MyFragment.kt` 加 "AI 视频" 入口项
   - `ui/config/AiConfigFragment.kt` 加 "AI 视频 Provider" 跳到 manage
9. 主题颜色：复用 `R.color.ai_*` 已有；如需新增到 `colors.xml` / `colors-night/`

## 阶段 5：集成 / 验证

1. `./gradlew :app:assembleDebug` 编译通过
2. `./gradlew :app:lintDebug` 无新错误
3. 跑 Room 迁移测试：
   - 用 Robolectric 模拟 98 → 99 升级，确认表创建成功、旧数据保留
4. 跑单元测试：`./gradlew :app:testDebugUnitTest`
5. 端到端冒烟（人工 / 设备）：
   - 添加一个 kling Provider
   - 提交"日落海面"提示词
   - 等 5~30min
   - 画廊里看到 success + 可播放
6. 异常路径：
   - 关网时 submit → 错误 toast + 行 `failed`
   - 杀进程后再开 → 任务续上

## 完成标准

- DB 98→99 迁移不丢数据
- 3 类 Provider 都能成功 submit + poll + 下载
- 画廊能浏览/搜索/分组/收藏/删除
- 杀进程能续任务
- 错误信息明确，不 ANR
