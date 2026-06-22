# AI 视频播放增强（P4）— 实现计划

关联设计：[2026-06-22-ai-video-p4-playback-design.md](file:///workspace/docs/superpowers/specs/2026-06-22-ai-video-p4-playback-design.md)
前置：P1 + P3 完成
执行：每步完成后跑 `./gradlew :app:assembleDebug`

## 阶段 0：偏好 / 资源准备

1. 在 `constant/PreferKey.kt` 加：
   - `videoAiSubtitleEnabled` (Boolean, 默认 false)
   - `videoAiSubtitleLanguage` (String, 默认 "zh-CN")
   - `videoAiDubbingEnabled` (Boolean, 默认 false)
   - `videoAiDubbingTtsProviderId` (String, 默认 "")
   - `videoAiChapterMarkerEnabled` (Boolean, 默认 true)
   - `videoAiInterpolationEnabled` (Boolean, 默认 false)
   - `videoAiSuperResolutionEnabled` (Boolean, 默认 false)
2. 字符串：
   - `video_ai_enhance` / `video_ai_subtitle` / `video_ai_dubbing` / `video_ai_chapter_marker` / `video_ai_interpolation` / `video_ai_super_resolution` / `video_ai_settings` / `video_ai_coming_soon` / `video_ai_test`
3. drawable：复用 `bg_video_chapter_item` 加 1dp 缩小版（`ic_video_chapter_marker.xml`）

## 阶段 1：控制器

1. 新增 `help/ai/video/VideoAiEnhanceController.kt`：
   - 构造参数：`ExoPlayer / Activity lifecycle`
   - `attach()`：按开关注册 listener
   - `detach()`：清空 listener
   - `applySettings(settings: VideoAiSettings)`
2. 新增 `help/ai/video/VideoAiSettings.kt` data class
3. 新增 `help/ai/video/VideoAiSubtitleRenderer.kt`：
   - 实现 `Player.Listener.onCues(Cues)`
   - 解析 SRT (从 P3 缓存)
   - 渲染到 `SubtitleView`（系统 `androidx.media3.ui.SubtitleView`）
4. 新增 `help/ai/video/VideoAiDubbingTrack.kt`：
   - 输入：当前 `MediaItem` + 字幕 cue 列表 + TTS 引擎配置
   - 用 `TTSReadAloudService` 已有 provider 合成每段 cue
   - 用 `ConcatenatingMediaSource` + `MergingMediaSource` 替换原 audio
5. 新增 `help/ai/video/VideoAiChapterOverlay.kt`：
   - 输入：P3 章节结果（`List<Pair<Long, String>>`）
   - 在 `VideoPlayerActivity` 的进度条上画小三角
6. 单元测试 `VideoAiSubtitleRendererTest`：解析 SRT + cue 匹配

## 阶段 2：UI 集成

1. 在 `ui/video/VideoPlayerActivity.kt`：
   - `onCreate` 末尾：`if (Book.type==VIDEO) VideoAiEnhanceController(this, player).attach()`
   - `onDestroy` 调 `detach()`
   - 菜单 `video_player.xml` 加 "AI 增强" 子菜单
2. 新增 `ui/video/VideoAiSettingsDialog.kt`：
   - 开关：字幕 / 配音 / 章节 / 插值 / 超分
   - 配音时显示 TTS 引擎选择
   - "测试" 按钮立即 reload
3. 在 `ui/video/ChapterAdapter.kt` 旁边加章节小三角（用 `ic_video_chapter_marker`）
4. 进度条：`VideoPlayerActivity` 自定义 `DetailSeekBar` 集成 `VideoAiChapterOverlay`
5. 字符串：见阶段 0

## 阶段 3：TTS 集成

1. 复用 `service/TTSReadAloudService.kt` 与 `help/TTS.kt` 的 TTS 抽象
2. 在 `VideoAiDubbingTrack` 里：
   - 调 `TTS.read aloud` 同一套 provider 配置
   - 输出 wav 临时文件
   - 拼接到 `mergedAudio.m4a`
3. 失败回退：原音 + 错误 toast，不静默失败

## 阶段 4：可选增强（v1 占位）

1. 插值 / 超分：v1 仅持久化偏好 + UI 开关 + 点击提示 "开发中"
2. 不实际调用任何 ffmpeg / GPU API

## 阶段 5：端到端验证

1. 准备一个带 P3 字幕缓存的视频书
2. `VideoAiSettingsDialog` 打开 AI 字幕 → 关闭 → 打开
3. 播放时画面下方显示字幕
4. 关闭字幕 → 字幕消失
5. 打开 AI 配音 + 选 TTS 引擎 → 播放时原声静音，AI 朗读同步
6. 关闭 AI 配音 → 原声恢复
7. 进度条显示章节小三角，点击跳转
8. 全关 → 播放器行为与 v1 之前完全一致

## 完成标准

- 5 个开关（字幕/配音/章节/插值/超分）持久化
- 字幕渲染流畅无 ANR
- AI 配音时间轴基本对齐（±300ms 误差可接受）
- 章节标记显示/隐藏可切换
- 插值/超分 v1 仅 UI 占位
- 任何增强失败都有回退路径
