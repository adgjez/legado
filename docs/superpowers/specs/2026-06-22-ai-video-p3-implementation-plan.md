# AI 视频理解 / 分析（P3）— 实现计划

关联设计：[2026-06-22-ai-video-p3-understanding-design.md](file:///workspace/docs/superpowers/specs/2026-06-22-ai-video-p3-understanding-design.md)
前置：P1 完成
执行：每步完成后跑 `./gradlew :app:assembleDebug`

## 阶段 0：DB 迁移

1. AppDatabase 版本升到 100
2. 复制 `99.json` 为 `100.json`
3. `data/DatabaseMigrations.kt` 加 `Migration_99_100`，创建 `ai_video_analysis` 表（见设计 §4.1）
4. 加 `AutoMigration(from = 99, to = 100)`（如果 Room 接受）或纯 SQL 迁移

## 阶段 1：数据层

1. 新增 `data/entities/AiVideoAnalysis.kt`（字段见设计 §4.1）
2. 新增 `data/dao/AiVideoAnalysisDao.kt`：`insertOrReplace / get(id) / byBookAndKind(bookId, kind) / byBook(bookId) / delete(id) / deleteByBook(bookId) / latestByKind(bookId, kind)`
3. 在 `AppDatabase` 注册

## 阶段 2：ASR 引擎抽象

1. 新增 `help/ai/asr/AsrEngine.kt`（接口 + `AsrSegment` data class）
2. 新增 `help/ai/asr/WhisperAsrEngine.kt`：
   - `suspend fun transcribe(audio, language): List<AsrSegment>`
   - POST `audio/transcriptions`，`response_format=verbose_json`，`timestamp_granularities=segment`
   - 解析 `segments[].start / end / text` 为 `AsrSegment`
3. 新增 `help/ai/asr/JsAsrEngine.kt`：
   - Rhino 跑 `transcribe(audio, language, provider)`，返回 `AsrSegment[]`
4. 新增 `help/ai/asr/LocalAsrEngine.kt`：
   - 抛 `NotImplementedError("not implemented")`
5. 新增 `help/ai/asr/AsrEngineFactory.kt`：根据 `AsrConfig` 返回对应实现
6. 单元测试 `WhisperAsrEngineTest`：mock OkHttp 验证 JSON 解析

## 阶段 3：核心分析服务

1. 新增 `help/ai/AiVideoAnalysisService.kt`：
   - `suspend fun summarize(bookId, kind="summary"): AiVideoAnalysis`
   - `suspend fun extractSubtitles(bookId, language): AiVideoAnalysis`
   - `suspend fun extractKeyFrames(bookId, n): AiVideoAnalysis`
   - `suspend fun detectChapters(bookId): AiVideoAnalysis`
   - `suspend fun extractCover(bookId): AiVideoAnalysis`
   - 流程：
     - 缓存命中直接返回
     - 抽 audio track → ASR → 字幕
     - 用 `AiChatService` 调 LLM（prompt 模板见设计 §6）
     - 章节/封面/关键帧调用对应引擎
     - 写表返回
2. 新增 `help/ai/VideoAudioExtractor.kt`：
   - 用 Media3 `Transformer` + `MediaSource` 解封装出音频
   - 输出 `filesDir/ai_video_analysis/{bookId}/audio.m4a`
3. 新增 `help/ai/VideoKeyFrameExtractor.kt`：
   - 等距抽 N 帧：`durationMs / n` 为间隔
   - 用 ExoPlayer 加载 + `Player.Listener.onPlaybackStateChanged` 抓 `Bitmap`
   - 输出到 `filesDir/ai_video_analysis/{bookId}/keyframe_{idx}.jpg`
4. 单元测试 `AiVideoAnalysisServiceTest`：mock audio/ASR/LLM/keyframes 验证流程

## 阶段 4：UI 层

1. 新增 `ui/main/ai/AiVideoAnalysisDialog.kt`（DialogFragment）：
   - 类型 chips：摘要 / 字幕 / 章节 / 关键帧 / 封面
   - 语种选择（仅字幕）
   - 开始/取消 + 进度条
2. 新增 `ui/main/ai/AiVideoAnalysisActivity.kt`：
   - 顶部 Tab：摘要 / 字幕 / 章节 / 关键帧
   - 摘要：Markdown（复用 `Markwon` + `ui/main/ai/AiMarkdownRender.kt`）
   - 字幕：可下载 SRT 按钮（导出到 download 目录）
   - 章节：列表，点击 `Intent { VideoPlayerActivity + startMs }`
   - 关键帧：网格（用 Glide）
3. 在 `AndroidManifest.xml` 注册 `AiVideoAnalysisActivity`
4. 在 `ui/book/info/BookInfoActivity.kt`：
   - 视频类书（`Book.type==VIDEO`）时，长按菜单加："AI 摘要 / AI 字幕 / AI 章节 / AI 封面"
5. 在 `ui/video/VideoPlayerActivity.kt`：
   - 顶栏菜单加 "AI 增强"（链接到 `AiVideoAnalysisDialog`）
6. 字符串：`ai_video_analysis_summary / subtitle / chapters / keyframes / cover / running / failed / success` 等

## 阶段 5：缓存策略

1. `AiVideoAnalysisService` 在调用前查 `byBookAndKind(bookId, kind).firstOrNull { it.status == "success" }`，命中直接返回
2. `failed` 状态行用户可点重试（删除旧行 + 重新调用）
3. 30 天未引用的 `success` 行自动清理（与 P1 一致策略）

## 阶段 6：端到端验证

1. 选一个本地视频书（`.mp4`）
2. 触发 AI 摘要：进度 → 完成 → 详情页显示 Markdown 摘要
3. 触发 AI 字幕：进度 → 完成 → 字幕 Tab 显示 SRT 内容
4. 触发 AI 章节：进度 → 完成 → 章节 Tab 显示列表
5. 触发 AI 关键帧：进度 → 完成 → 关键帧 Tab 显示网格
6. 重复请求：直接返回缓存
7. 失败重试：删除 failed 行 → 重新调用 → 成功

## 完成标准

- 5 个分析能力全部能完成
- 缓存命中避免重复 LLM/ASR 调用
- 异常（音频抽取失败 / ASR 失败 / LLM 超时）有兜底
- 进度可取消
- 不阻塞主线程
