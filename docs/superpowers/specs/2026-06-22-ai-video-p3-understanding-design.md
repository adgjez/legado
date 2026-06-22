# AI 视频理解 / 分析（P3）— 设计文档

日期：2026-06-22
阶段：P3 / 4（依赖 P1，弱依赖 P2）
目标：对书库中"视频"类（`Book.type=VIDEO`、RSS 视频）调用 AI 做摘要、章节拆分、关键帧提取、字幕生成、封面抽取。

## 1. 目标

- 提供一个 `AiVideoAnalysisService`（高层 API）：
  - `summarize(bookId)` — LLM 总结（基于"提取的字幕"或"抓取的元信息"）
  - `extractSubtitles(bookId, language)` — 提取字幕（两种实现：自建本地 ASR、调用远端 ASR）
  - `extractKeyFrames(bookId, n)` — 抽 N 个关键帧（封面/缩略图）
  - `detectChapters(bookId)` — 章节切分（基于字幕/静音检测）
  - `extractCover(bookId)` — 抽取首帧/精彩帧做书封面
- 缓存分析结果：新建 `ai_video_analysis` 表（与 bookId 关联）
- 入口：
  - `BookInfoActivity` 长按菜单加 "AI 摘要 / AI 字幕 / AI 章节 / AI 封面"
  - `VideoPlayerActivity` 顶栏加"AI"按钮弹底部 sheet
  - `RssArticlesFragment` 视频类条目长按

## 2. 不在 P3
- 实时播放增强（→ P4）
- 多模态分析（视频帧 + 字幕联合喂给多模态 LLM）放 v2 再说

## 3. 架构

```
┌──────────────────────────────────────────────────────────┐
│ UI                                                       │
│  BookInfoActivity / VideoPlayerActivity / RssArticle     │
│  AiVideoAnalysisDialog (底部 sheet)                      │
│  AiVideoAnalysisActivity (结果详情，可分享)              │
└──────────────────────────────────────────────────────────┘
                          │
┌────────────────────────▼─────────────────────────────────┐
│ Service                                                  │
│  AiVideoAnalysisService                                  │
│   ├─ extract audio track via Media3 Extractor            │
│   ├─ transcribe via AsrEngine (Whisper API / Vosk / 云) │
│   ├─ summarize via LlmEngine (现有 AiChatService)        │
│   └─ keyframe via Media3 + 首帧抓取                       │
│  AiVideoAnalysisManager                                  │
│   └─ 缓存 + 进度 + 取消                                  │
└──────────────────────────────────────────────────────────┘
                          │
┌────────────────────────▼─────────────────────────────────┐
│ Data                                                     │
│  AiVideoAnalysis (entity)                                │
│  AiVideoAnalysisDao                                      │
│  Migration_99_100                                        │
└──────────────────────────────────────────────────────────┘
```

## 4. 数据层

### 4.1 `ai_video_analysis` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long PK autogen | |
| `bookId` | String | 关联 `Book.bookId` 或 RSS article id |
| `kind` | String | `summary / subtitle / chapters / keyframes / cover` |
| `language` | String | 字幕语种 |
| `payloadJson` | String | 结果 JSON（字幕 SRT 文本/章节数组/关键帧路径数组/封面路径） |
| `model` | String | 使用的模型 |
| `providerId` | String | |
| `status` | String | `pending/running/success/failed` |
| `failReason` | String | |
| `createdAt/updatedAt` | Long | |

### 4.2 DAO
- `insertOrReplace / get / byBookAndKind / delete`
- 索引：`(bookId, kind)`

## 5. ASR 引擎抽象

```kotlin
interface AsrEngine {
    suspend fun transcribe(audio: File, language: String): List<AsrSegment>
}
data class AsrSegment(val startMs: Long, val endMs: Long, val text: String)
```

实现：
- `WhisperAsrEngine` — 调 `https://api.openai.com/v1/audio/transcriptions`（或可配置 baseUrl）
- `JsAsrEngine` — Rhino 跑用户脚本（与书源 JS 一致）
- `LocalAsrEngine` — 留接口，v1 不实现（占位返回 `error("not implemented")`），避免阻塞其它 ASR 实现

## 6. LLM 引擎
复用 `AiChatService` 现有 LLM 调用，加 `summarize` / `extractChapters` 两个 prompt 模板：
- `summarize`: "请基于以下字幕内容生成 200 字以内的中文摘要，分段..."
- `extractChapters`: "请基于以下带时间戳的字幕，输出 5~10 个章节，每章包含 startMs/title"

## 7. 关键帧提取
- 用 `Media3 ExoPlayer` + `Transformer` 解封装；不在视频上播放
- 算法 v1 简化：等距抽帧（每 `durationMs/n` 取 1 帧）+ GL 渲染保存 jpg
- 关键帧路径写表 + 复制到 `filesDir/ai_videos/keyframe/{bookId}_{idx}.jpg`

## 8. UI

### 8.1 `AiVideoAnalysisDialog`
- 类型选择 chips：摘要 / 字幕 / 章节 / 封面
- 语种选择（仅字幕）
- 开始/取消按钮
- 进度条 + 当前阶段标签

### 8.2 `AiVideoAnalysisActivity`
- 顶部 Tab：摘要 / 字幕 / 章节 / 关键帧
- 摘要：Markdown 渲染
- 字幕：可下载 SRT
- 章节：列表，点击跳转 `VideoPlayerActivity`（intent extra `startMs`）
- 关键帧：网格，点击进图片预览

## 9. 性能 / 错误
- 音频抽取失败的兜底：仅做"元信息总结"（书名/简介 + 视频 URL）
- LLM 超时：复用 `AiChatService` 超时策略
- 取消：协程 `Job.cancel()`，清理临时音频文件

## 10. 验收标准
- 选一个本地视频书，能生成摘要 + 字幕 + 关键帧
- 字幕结果可在播放器里挂载（v1 仅展示，SRT 加载器属 P4）
- 重复请求走缓存（kind+language 命中直接返回）

## 11. 风险
- 音视频解码兼容性：兜底用 ExoPlayer 自带的 codec
- 远端 ASR 成本：默认 Whisper API，可关闭
- 长视频（>2h）摘要：切片处理
