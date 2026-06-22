# AI 视频工具集成（P2）— 实现计划

关联设计：[2026-06-22-ai-video-p2-ai-tool-design.md](file:///workspace/docs/superpowers/specs/2026-06-22-ai-video-p2-ai-tool-design.md)
前置：P1 完成
执行：每步完成后跑 `./gradlew :app:assembleDebug`

## 阶段 0：TDD 起点

1. 在 `app/src/test/java/io/legado/app/help/ai/AiVideoToolTest.kt` 写测试桩（mock `AiVideoService` / `AiVideoGalleryManager` / `BookCharacterDao`）
2. 期望 5 个 tool 的 JSON schema 与设计 §3.1 / §3.2 / §3.3 / §3.4 一致

## 阶段 1：工具实现

1. 新增 `help/ai/AiVideoTool.kt`：
   - `fun resolvedTools(): List<AiResolvedTool>` 返回 5 个 tool
   - `generate_video`：
     - 参数：`prompt / negativePrompt / providerId / firstFrame / aspectRatio / durationSec`
     - 调 `AiVideoService.submitAndStore`
     - 返回 `{"ok":true, "taskId":"...", "videoId":"...", "status":"pending"}` 或 `{"ok":false, "error":"..."}`
   - `list_ai_gallery_videos`：
     - 参数：`bookKey / characterId / sourceType / status / favorite / keyword / limit`
     - 调 `aiGeneratedVideoDao.search / byBook / byGroup / favorites`
     - 返回 `{"ok":true, "items":[{"id":"...","name":"...","durationMs":...,"status":"...","coverPath":"..."}, ...]}`
   - `get_ai_gallery_video`：
     - 参数：`videoId`
     - 返回完整字段 JSON
   - `set_book_character_avatar_from_video_gallery`：
     - 参数：`characterId / videoId / frameIndexMs`（默认 0）
     - 用 Media3 ExoPlayer seek 到 frameIndexMs 抽 Bitmap → 保存到 `filesDir/book_character/{id}_avatar.jpg`
     - 更新 `bookCharacterDao.updateAvatar(id, path)`
     - 返回 `{"ok":true, "avatarPath":"..."}`
   - `generate_book_character_short_video`：
     - 参数：`characterId / stylePrompt?`
     - 取角色名+外貌描述 → 拼 prompt → 调 `generate_video`，`sourceType=character_short_video`
2. 在 `help/ai/AiToolRegistry.kt`：
   - 把 5 个 tool 加到 `nativeTools` 列表
   - `defaultEnabledTools` 集合加 `generate_video / list_ai_gallery_videos / get_ai_gallery_video / set_book_character_avatar_from_video_gallery / generate_book_character_short_video`
   - `nativeToolLabels` 加 5 个中文标签
3. 在 `help/ai/AiChatService.kt`：
   - 新增 `longRunningToolNames` 集合：`generate_video / generate_book_character_short_video`
   - 加入现有 `imageToolNames` 类似的 tool-name 集合
4. 单元测试 `AiVideoToolTest`：覆盖 5 个 tool 的正常返回与错误返回

## 阶段 2：聊天 UI 集成

1. 在 `constant/EventBus.kt` 已经有 `AI_VIDEO_COMPLETED / AI_VIDEO_FAILED` 事件（P1 已加）
2. 在 `ui/main/ai/AiChatViewModel.kt`：
   - `init { observeAiVideoEvents() }`：订阅 `LiveEventBus.get(AI_VIDEO_COMPLETED)`
   - 完成时插入一条系统消息：`AiChatMessage(kind = "video_completed", text = "✅ AI 视频已生成：$name", attachmentVideoId = videoId)`
   - 失败时插入：`kind = "video_failed", text = "❌ AI 视频生成失败：$reason"`
3. 在 `ui/main/ai/AiChatModels.kt` 加 `AiChatMessage.kind` 枚举值 `video_completed / video_failed`
4. 在 `ui/main/ai/compose/AiChatScreen.kt` 渲染对应消息样式（卡片 + 缩略图 + 跳转画廊）
5. 字符串：`<string name="ai_video_completed">AI 视频已生成：%1$s</string>` / `ai_video_failed` / `ai_video_view_in_gallery`

## 阶段 3：端到端验证

1. 启动 app，开启 AI 聊天
2. 让 LLM 调 `generate_video` 提交任务
3. 任务完成时聊天窗口出现提示卡片
4. 点击卡片跳到画廊
5. 让 LLM 调 `list_ai_gallery_videos`，LLM 能正确展示视频列表
6. 让 LLM 调 `set_book_character_avatar_from_video_gallery`，打开角色卡确认头像更新
7. 异常路径：LLM 调 `generate_video` 但 prompt 为空 → 工具返回 `ok:false error:prompt is empty`，LLM 调整提示词

## 完成标准

- 5 个 tool 在 AI 聊天里能正常被 LLM 调用
- 工具 JSON schema 与 OpenAI function calling 兼容
- 工具返回包含 `ok / success` 字段（与现有工具保持一致）
- 任务完成时聊天窗口显示提示卡片
- 错误路径全部走 JSON 返回，不抛异常
