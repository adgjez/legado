# AI 视频播放增强（P4）— 设计文档

日期：2026-06-22
阶段：P4 / 4（依赖 P1 + P3）
目标：在不破坏现有 `VideoPlayerActivity` 行为的前提下，提供一组可独立开关的 AI 增强能力。

## 1. 目标

增强项（每个可独立开关，默认全部关闭）：

1. **AI 字幕 (AI Subtitle)**
   - 数据源：优先 P3 缓存 `subtitle`；否则在播放时触发一次 ASR（用户可拒）
   - 渲染：复用 `VideoPlayerActivity` 的字幕轨，自定义 `SubtitleView` 或 `CaptioningManager`
2. **AI 配音 (AI Dubbing)**
   - 流程：抽取音轨 → 调 TTS（用 `TTSReadAloudService` 已有 provider）→ 输出 wav → 替换原音轨
   - 开关 + 语速 + 音色选择（沿用 TTS 引擎选择器）
3. **AI 关键帧插值 (Frame Interpolation, v1 轻量)**
   - 仅在 24/30fps 视频档位提示开启；本地 v1 提供开关但实际调用占位（说明：依赖 GPU/ffmpeg，v1 仅 UI 开关 + 提示"开发中"）
4. **AI 视频超分 (Super Resolution, v1 轻量)**
   - 同上：v1 仅 UI 开关
5. **AI 章节 (Chapter Detection)**
   - 复用 P3 章节结果，在播放进度条上画章节标记

## 2. 不在 P4
- 实时多模态问答（v2）
- 视频内容摘要（→ P3）
- AI 工具触发视频生成（→ P2）

## 3. 架构

```
┌──────────────────────────────────────────────────────────┐
│ UI                                                       │
│  VideoPlayerActivity (扩展，菜单加 AI 项)                │
│  VideoAiSettingsDialog (开关 + TTS 引擎选择)             │
│  VideoAiChapterMarker (进度条上的小三角)                 │
└──────────────────────────────────────────────────────────┘
                          │
┌────────────────────────▼─────────────────────────────────┐
│ Service                                                  │
│  VideoAiEnhanceController (Activity onCreate 时按开关装) │
│   ├─ VideoAiSubtitleRenderer                             │
│   ├─ VideoAiDubbingTrack (替换 ExoPlayer 音轨)           │
│   ├─ VideoAiChapterOverlay                               │
│   └─ VideoAiCaptionLoader (P3 数据源)                    │
└──────────────────────────────────────────────────────────┘
                          │
┌────────────────────────▼─────────────────────────────────┐
│ Data                                                     │
│  PreferKey: videoAiSubtitle/videoAiDubbing/...           │
│  沿用 P3 的 ai_video_analysis 表                         │
└──────────────────────────────────────────────────────────┘
```

## 4. 字幕渲染
- `VideoAiSubtitleRenderer` 实现 `Player.Listener.onCues`
- 解析 SRT，按 `currentPosition` 找到当前 cue
- 样式：底部居中白色描边；遵循 `ReadBookConfig` 的颜色 token
- 关闭时清空 listener

## 5. AI 配音
- 输入：当前视频 `MediaItem` 的 URI
- 流程：
  1. `ExoPlayer` 静音原音
  2. 用 TTS 引擎按字幕 cue 顺序合成 `mp3/wav`（P3 字幕做时间轴）
  3. 用 `ConcatenatingMediaSource` 把生成的片段拼成 `mergedAudio.m4a`
  4. 用 `MergingMediaSource` 替换原 audio track
- 失败回退：恢复原音 + toast

## 6. 配置 / 偏好
新增 `PreferKey`：
- `videoAiSubtitleEnabled = false`
- `videoAiSubtitleLanguage = "zh-CN"`
- `videoAiDubbingEnabled = false`
- `videoAiDubbingTtsProviderId = ""`（沿用 `HttpTTS`）
- `videoAiChapterMarkerEnabled = true`
- `videoAiInterpolationEnabled = false`（v1 仅持久化）
- `videoAiSuperResolutionEnabled = false`（v1 仅持久化）

## 7. UI 调整
- `VideoPlayerActivity` 菜单加 "AI 增强" 子菜单（仅当 `Book.type==VIDEO` 时显示）
- `VideoAiSettingsDialog`：开关 + 引擎选择 + 立即测试
- 进度条底部画章节小三角（沿用 `bg_video_chapter_item` 的 drawable，加 1dp 缩小版）

## 8. 性能
- 字幕渲染零额外 IO（cue 都在内存里）
- AI 配音异步合成；播完前预生成下一段
- 章节标记只在 `videoAiChapterMarkerEnabled=true` 时计算

## 9. 错误处理
- TTS 失败：原音 + 弹错误
- 字幕缺失：弹"是否立即生成字幕？" → 调 P3

## 10. 验收标准
- 打开一个带 P3 缓存字幕的视频，开关"AI 字幕"后画面下方出现字幕
- 开启 AI 配音后，原声静音，AI 朗读同步
- 章节标记显示在进度条上
- 全关后播放器行为与 P1 之前完全一致

## 11. 风险
- 配音时间轴漂移 → 用字幕 cue startMs 对齐
- 视频类型识别：仅 `Book.type==VIDEO` 与 `RssArticle` 视频才进入增强路径
- 后续 P5+ 可以接入真正 ffmpeg 实现超分/插值
