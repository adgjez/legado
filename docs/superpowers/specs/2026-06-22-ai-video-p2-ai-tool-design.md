# AI 视频工具集成（P2）— 设计文档

日期：2026-06-22
阶段：P2 / 4（依赖 P1）
目标：在 AI 聊天 (`AiChatService`) 中注册一组工具，让 LLM 能主动调用 AI 视频生成、查询画廊、绑定到角色/书籍。

## 1. 目标

- 复用 P1 的 `AiVideoService` / `AiVideoGalleryManager` / `AiVideoProviderConfig`
- 在 `AiToolRegistry` 注册新工具：
  - `generate_video` — 提交文生视频/图生视频任务
  - `list_ai_gallery_videos` — 列出画廊视频
  - `get_ai_gallery_video` — 取一条视频元信息
  - `set_book_character_avatar_from_video_gallery` — 从画廊视频抽帧做角色头像
  - `generate_book_character_short_video` — 角色卡对应短视（基于角色描述 prompt）
- 工具执行后可通过 `LiveEventBus` 让 UI 立即刷新

## 2. 不在 P2
- 视频理解/分析（→ P3）
- 播放增强（→ P4）

## 3. 关键流程

### 3.1 `generate_video`
LLM 调用 → 校验 prompt 与 providerId（可选）→ 调 `AiVideoService.submitAndStore`（异步，1~30 min）→ 返回 `taskId` 字符串给 LLM，LLM 用自然语言告诉用户稍后可在画廊查看；后台完成时通过 `AI_VIDEO_COMPLETED` 事件通知聊天窗口可插入一条 tool 提醒消息。

**JSON Schema**（与 OpenAI tool calling 对齐）：
```json
{
  "type": "function",
  "function": {
    "name": "generate_video",
    "description": "提交一个 AI 视频生成任务。生成通常需要 1~30 分钟，提交后任务在后台运行，结果会写入 AI 视频画廊。",
    "parameters": {
      "type": "object",
      "properties": {
        "prompt": {"type": "string", "description": "文本提示词"},
        "negativePrompt": {"type": "string"},
        "providerId": {"type": "string"},
        "firstFrame": {"type": "string", "description": "图生视频首帧 URL 或本地路径，可选"},
        "aspectRatio": {"type": "string", "enum": ["16:9","9:16","1:1","4:3"]},
        "durationSec": {"type": "integer", "minimum": 1, "maximum": 60}
      },
      "required": ["prompt"]
    }
  }
}
```

### 3.2 `list_ai_gallery_videos` / `get_ai_gallery_video`
- 复用 `AiGeneratedVideoDao.search` / `get`
- 关键字段过滤：`bookKey` / `characterId` / `sourceType` / `status` / `favorite`
- 列表返回：id, name, prompt, providerName, model, durationMs, coverPath, status, createdAt

### 3.3 `set_book_character_avatar_from_video_gallery`
- 输入 `characterId`, `videoId`, `frameIndexMs`（默认取首帧）
- 抽帧：复用 Media3 ExoPlayer 加载本地 mp4 → seek 到 `frameIndexMs` → 读 surface 转 Bitmap → 保存到 `filesDir/book_character/{id}_avatar.jpg` + 更新 `BookCharacter.avatar`

### 3.4 `generate_book_character_short_video`
- 输入 `characterId`（必填），可选 `stylePrompt`
- 取角色名 + 描述（name + appearance/prompt）拼成 prompt，调 `generate_video`
- `sourceType = "character_short_video"`，自动绑 `characterId`

## 4. 文件清单

| 文件 | 类型 | 说明 |
|---|---|---|
| `help/ai/AiVideoTool.kt` | 新增 | 5 个 tool 的实现 + JSON schema |
| `help/ai/AiToolRegistry.kt` | 修改 | 注册 5 个新 tool，加入 `defaultEnabledTools` |
| `help/ai/AiChatService.kt` | 修改 | 将 `generate_video` / `generate_book_character_short_video` 加入 `imageToolNames` 类似集合（影响 `retryableToolNames`），新集合名 `longRunningToolNames` |
| `ui/main/ai/AiChatViewModel.kt` | 修改 | 订阅 `AI_VIDEO_COMPLETED` 事件，在聊天窗口插入系统提示气泡 |

## 5. LiveEventBus 事件

- `AI_VIDEO_SUBMITTED`（P1 已发）— taskId
- `AI_VIDEO_PROGRESS` — taskId, progress
- `AI_VIDEO_COMPLETED` — taskId, success, videoId?
- `AI_VIDEO_FAILED` — taskId, reason

在 `constant/EventBus.kt` 加常量；发送方在 `AiVideoTaskService`；接收方在 `AiChatViewModel`。

## 6. 错误处理
- prompt 空 → 工具返回 `{"ok":false,"error":"prompt is empty"}`
- provider 不可用 → 同 AI Image 工具策略
- 生成失败 → 工具返回 `{"ok":true,"taskId":"...","status":"failed","reason":"..."}`，由 LLM 决定是否重试

## 7. 验收标准
- AI 聊天里说"帮我生成一个日落海面的短视频"，LLM 调 `generate_video`，返回 `taskId`，对话里告诉用户稍后查看
- 任务完成后聊天窗口出现 "✅ AI 视频已生成：xxx" 提示卡片
- 在画廊里能用 `list_ai_gallery_videos` 查到
- 角色卡能"从画廊视频设置头像"

## 8. 风险
- AI 视频生成耗时长，会撑爆 tool-call 轮次 → 调 `MAX_TOOL_ROUNDS=12` 保持；`generate_video` / `generate_book_character_short_video` 立即返回 `taskId`，不阻塞当前轮
- LLM 滥用 → 加 `maxConcurrentVideoTasks` 限制
