# legado AI 模块开发文档

## 架构概览

AI 子系统分为四层：**配置层**（AppConfig）、**服务层**（help/ai/）、**数据层**（entities + dao）、**UI 层**（ui/）。

```
┌─────────────────────────────────────────────────────┐
│  UI 层：Activity / Fragment / Composable             │
│  AiChatActivity  AiConfigFragment  Ai*Gallery        │
├─────────────────────────────────────────────────────┤
│  服务层：help/ai/（约 55 个文件）                      │
│  AiChatService / AiAgentRuntime / AiToolRegistry     │
│  Ai*Service / Ai*GalleryManager / AiMcpClient        │
├─────────────────────────────────────────────────────┤
│  数据层：entities + dao（20 实体 + 16 DAO）            │
│  Room 数据库，版本 121                                │
├─────────────────────────────────────────────────────┤
│  配置层：AppConfig（PreferKey 持久化）                 │
└─────────────────────────────────────────────────────┘
```

---

## 一、服务层（help/ai/）

### 1.1 聊天与 Agent 运行时

| 类 | 文件 | 职责 |
|---|---|---|
| `AiChatService` | `AiChatService.kt` | 聊天入口。支持 `chat()` 阻塞式与 `chatStream()` 流式，处理 OpenAI / OAI Responses 两种 API 模式，Tool Call 流式解析，Skill/Prompt 拼接，Usage 统计 |
| `AiAgentRuntime` | `AiAgentRuntime.kt` | Agent 工具循环。`runToolLoop()` 最多 12 轮，包含思考状态回调、工具执行验证、错误恢复 |
| `AiAgentPlanner` | `AiAgentPlanner.kt` | 计划器。根据用户目标自动生成执行计划（understand → read → execute → validate → respond） |
| `AiAgentValidator` | `AiAgentValidator.kt` | 结果校验器。验证 JSON 有效性、ok/success 字段、写入完整性、retryable 判定 |
| `AiAgentStateStore` | `AiAgentStateStore.kt` | 状态持久化。管理 Session/Job/Trace 生命周期，租约机制防并发 |
| `AiToolExecutor` | `AiToolExecutor.kt` | 工具执行器。`execute()` 同步，`executeAsync()` 异步（视频/音频），`executeStory()` 分镜流水线，支持超时和重试 |
| `AiToolRegistry` | `AiToolRegistry.kt` | 工具注册中心。管理 56 个原生工具和 MCP 工具的注册、版本迁移（v1→v12）、启用/禁用、分类标签 |

### 1.2 上下文与记忆

| 类 | 文件 | 职责 |
|---|---|---|
| `AiContextManager` | `AiContextManager.kt` | 上下文压缩。`prepare()` 基于 token 估算自动压缩对话历史，生成摘要 |
| `AiMemoryStore` | `AiMemoryStore.kt` | 长期记忆。upsert/delete 记忆条目和片段，FTS 全文搜索，scope 作用域过滤（global/book/session/character/roleplay） |

### 1.3 生成任务管理

| 类 | 文件 | 职责 |
|---|---|---|
| `AiGenTaskManager` | `AiGenTaskManager.kt` | 任务生命周期。创建/提交/进度更新/完成/失败，通过 Flow 推送状态变更 |
| `AiGenPoller` | `AiGenPoller.kt` | 轮询器。前台/后台双通道轮询生成任务状态，指数退避，超时取消 |
| `AiTaskKeepAlive` | `AiTaskKeepAlive.kt` | 保活工具。后台任务在应用切后台时继续运行 |

### 1.4 生成服务（Image/Video/Audio）

| 类 | 文件 | 职责 |
|---|---|---|
| `AiImageService` | `AiImageService.kt` | 图片生成。支持 OpenAI 兼容接口和 JS 脚本两种 provider，生成/批量生成/编辑/inpaint |
| `AiVideoService` | `AiVideoService.kt` | 视频生成。异步流程：submit → poll → download，最大 200MB |
| `AiAudioService` | `AiAudioService.kt` | 音频生成。异步流程：submit → poll → download，最大 50MB，支持 music/sfx |

### 1.5 画廊管理

| 类 | 文件 | 职责 |
|---|---|---|
| `AiImageGalleryManager` | `AiImageGalleryManager.kt` | 图片画廊。保存、查询、分组、临时清理 |
| `AiVideoGalleryManager` | `AiVideoGalleryManager.kt` | 视频画廊。保存、查询、分组 |
| `AiAudioGalleryManager` | `AiAudioGalleryManager.kt` | 音频画廊。保存、查询、分组 |

### 1.6 分镜与故事创作

| 类 | 文件 | 职责 |
|---|---|---|
| `AiStoryPipeline` | `AiStoryPipeline.kt` | 分镜流水线。编排：planning → generating_images → generating_videos → done |
| `AiStoryDirector` | `AiStoryDirector.kt` | 分镜导演。分析章节文本生成分镜脚本 |

### 1.7 朗读角色与配乐

| 类 | 文件 | 职责 |
|---|---|---|
| `AiReadAloudRoleService` | `AiReadAloudRoleService.kt` | 朗读角色分配。AI 驱动的段落角色识别和配音路由 |
| `AiReadAloudBgmService` | `AiReadAloudBgmService.kt` | 朗读配乐。AI 驱动的段落配乐推荐 |
| `AiReadAloudUsageRecorder` | `AiReadAloudUsageRecorder.kt` | 使用记录。记录 role/bgm/sfx/audio 各类型消耗 |

### 1.8 文本净化与章节摘要

| 类 | 文件 | 职责 |
|---|---|---|
| `AiSanitizeService` | `AiSanitizeService.kt` | 文本净化。通过 AI 清理广告/无关内容 |
| `AiChapterSummaryService` | `AiChapterSummaryService.kt` | 章节摘要。AI 生成章节摘要 |

### 1.9 MCP 客户端

| 类 | 文件 | 职责 |
|---|---|---|
| `AiMcpClient` | `AiMcpClient.kt` | MCP 协议客户端。实现 MCP 2025-06-18 协议，JSON-RPC 2.0 over HTTP/SSE，session 管理，tools/list 和 tools/call，工具缓存（60s TTL），自动重连 |

---

## 二、工具系统

### 2.1 工具定义清单

所有工具定义在 `help/ai/` 中，每个文件对应一组 function calling 工具：

| 工具组 | 工具数 | 主要工具 |
|---|---|---|
| `AiBookshelfTool` | 7 | query_bookshelf, get_bookshelf_book_info, manage_bookshelf_group/tag, set_bookshelf_book_group/tags, query_read_records |
| `AiLibraryTool` | 3 | list_book_chapters, search_book_chapter_content, read_book_chapter_content |
| `AiBookSourceTool` | 6 | list/search/create/get/update/debug_book_source, fetch_source_html |
| `AiReadingNetworkTool` | 3 | reading_ajax, reading_webview, capture_web_requests |
| `AiBookCharacterTool` | 8 | list/upsert/delete_book_character, list/upsert/delete_book_character_relation, set/generate_book_character_avatar |
| `AiWorldBookTool` | 9 | list/upsert/delete_world_book, upsert/delete_world_book_entry, list/upsert/delete_world_book_binding, import/export_world_book_json |
| `AiImageTool` | 2 | generate_image, generate_book_character_avatar |
| `AiImageToolEnhanced` | 3 | generate_images, edit_image, inpaint_image |
| `AiVideoTool` | 4 | generate_video, generate_video_from_image, extract_video_frame, continue_video_from_frame |
| `AiAudioTool` | 2 | generate_music, generate_sound_effect |
| `AiStoryTool` | 1 | generate_scene |
| `AiSanitizeTool` | 1 | sanitize_text |
| `AiReadAloudBgmTool` | 2 | list_read_aloud_bgm_catalog, assign_read_aloud_bgm_ranges |
| `AiSettingsTool` | 3 | get_app_settings, set_app_setting, set_app_settings_batch |
| `AiTavilyTool` | 1 | search_web_tavily |

**总计：56 个原生工具**（当前版本 v12 默认启用）。MCP 工具通过 `AiMcpClient` 动态注册，别名格式 `mcp_{server}_{tool}`。

### 2.2 添加新工具

1. 在 `help/ai/` 中创建 `AiXxxTool.kt`，定义 `object` 包含 `fun createTool(): Tool`
2. 在 `AiToolRegistry.kt` 的 `nativeTools` 列表中添加条目
3. 递增 `aiEnabledToolNamesVersion`（当前 v12）
4. 在 `AiToolRegistry.resolveExistingToolNames()` 中处理版本迁移

---

## 三、数据层

### 3.1 实体（20 个）

| 实体 | 表名 | 核心字段 |
|---|---|---|
| `AiGenTask` | `ai_gen_tasks` | modality, status, prompt, providerId, remoteTaskId, resultId, progress, costActual |
| `AiGenVoucher` | `ai_gen_vouchers` | taskId, modality, providerId, costEstimate, costActual, durationSeconds |
| `AiGenFailureLog` | `ai_gen_failure_logs` | modality, providerId, errorMessage, errorType, retryCount |
| `AiAgentSession` | `ai_agent_sessions` | scope, status, currentGoal, contextJson, lastError |
| `AiAgentJob` | `ai_agent_jobs` | type, status, inputJson, checkpointJson, outputJson, leaseUntil |
| `AiAgentTrace` | `ai_agent_traces` | round, eventType, payloadJson |
| `AiMemoryItem` | `ai_memory_items` | scope, type, subject/predicate/objectValue, confidence, importance, fingerprint |
| `AiMemoryItemFts` | `ai_memory_items_fts` | FTS4 全文索引 |
| `AiMemoryFragment` | `ai_memory_fragments` | scope, bookKey, chapterIndex, content, contentHash, importance |
| `AiMemoryFragmentFts` | `ai_memory_fragments_fts` | FTS4 全文索引 |
| `AiGeneratedImage` | `ai_generated_images` | prompt, localPath, bookKey, chapterIndex, characterId, favorite, groupId, generationMode |
| `AiGeneratedVideo` | `ai_generated_videos` | prompt, localPath, thumbnailPath, duration, generationMode, inputImageId, favorite |
| `AiGeneratedAudio` | `ai_generated_audios` | prompt, localPath, duration, format, audioType, favorite |
| `AiImageGroup` | `ai_image_groups` | name, sortOrder |
| `AiVideoGroup` | `ai_video_groups` | name, sortOrder |
| `AiAudioGroup` | `ai_audio_groups` | name, sortOrder |
| `AiStoryScene` | `ai_story_scenes` | playlistId, sceneIndex, narrativeText, visualPrompt, cameraControl, imageId, videoId, audioId, status |
| `AiStoryPlaylist` | `ai_story_playlists` | bookKey, chapterTitle, sceneCount, totalDuration, status |
| `AiPurifiedTextCache` | `ai_purified_text_cache` | bookKey+chapterIndex+intensity(unique), contentHash, sanitizedText |
| `AiReadAloudRoleCache` | `ai_read_aloud_role_caches` | cacheKey, bookUrl, chapterIndex, contentHash, segmentsJson, status |
| `AiReadAloudUsageRecord` | `ai_read_aloud_usage_records` | type, status, providerName, modelId, elapsedMillis, inputTokens, outputTokens |

### 3.2 数据库版本与迁移

当前版本：**121**。数据库文件：`AppDatabase.kt`，迁移定义：`DatabaseMigrations.kt`。

**修改 schema 的步骤：**
1. 修改实体类
2. 在 `DatabaseMigrations.kt` 中定义新迁移（如 `migration_121_122`）
3. 将迁移添加到 `migrations` 数组
4. 在 `AppDatabase.kt` 中递增 `version`

**注意：** 实体中的 `@Index(name=...)` 和 `@ColumnInfo(defaultValue=...)` 必须与迁移 DDL 中创建的索引名称和默认值完全一致，否则 Room 会抛出 identity hash 验证失败。

---

## 四、UI 层

### 4.1 主要界面

| 界面 | 文件 | 用途 |
|---|---|---|
| AI 聊天 | `ui/main/ai/AiChatActivity.kt` | 主聊天界面，Compose 实现。支持角色助手、历史管理、模型切换、Skill/MCP/世界书配置、自动朗读 |
| AI 设置 | `ui/config/AiConfigFragment.kt` | 全局 AI 配置。Provider/Model/Skill/工具/MCP/图片/视频/音频供应商/上下文压缩/世界书/画廊入口 |
| 图片画廊 | `ui/main/ai/AiImageGalleryActivity.kt` | AI 图片浏览、分组、收藏、搜索 |
| 视频画廊 | `ui/main/ai/AiVideoGalleryActivity.kt` | AI 视频浏览、分组、收藏 |
| 统一画廊 | `ui/main/ai/AiUnifiedGalleryActivity.kt` | 图片+视频+音频统一管理 |
| 阅读 AI 面板 | `ui/book/read/ReadAiFloatingPanel.kt` | 阅读界面中的 AI 对话悬浮窗 |
| 阅读摘要面板 | `ui/book/read/ReadAiSummaryPanel.kt` | 章节摘要展示面板 |

### 4.2 Compose 组件

| 组件 | 文件 | 用途 |
|---|---|---|
| `AiChatScreen` | `ui/main/ai/compose/AiChatScreen.kt` | 聊天界面的 Compose 路由 |
| `AiMarkdownRender` | `ui/main/ai/AiMarkdownRender.kt` | Markdown 渲染（基于 Markwon） |
| `AiChatSpeechPlayer` | `ui/main/ai/AiChatSpeechPlayer.kt` | 聊天语音播放（TTS + HTTP 音频） |
| `AiBookCreationStrip` | `ui/main/ai/AiBookCreationStrip.kt` | 书籍详情页 AI 创作内容条 |
| `AiGenDialog` | `ui/main/ai/AiGenDialog.kt` | 生成任务对话框 |
| `AiSanitizeDiffDialog` | `ui/main/ai/AiSanitizeDiffDialog.kt` | 文本净化对比弹窗 |

---

## 五、配置系统

### 5.1 核心配置枚举

| 配置项 | PreferKey | 默认值 | 说明 |
|---|---|---|---|
| 总开关 | `aiAssistantEnabled` | false | 关闭后所有 AI 功能不可用 |
| 当前 Provider | `aiCurrentProviderId` | null | 选中后聊天功能可用 |
| 当前模型 | `aiCurrentModelId` | null | 选中后聊天功能可用 |
| 提问模型 | `aiAskModelId` | null | 用于"问 AI"场景 |
| 摘要模型 | `aiSummaryModelId` | null | 用于章节摘要 |
| 系统提示词 | `aiSystemPrompt` | "" | 全局系统提示词 |
| 回车发送 | `aiEnterToSend` | true | 聊天输入回车发送 |
| 上下文压缩 | `aiContextCompressionEnabled` | false | 是否自动压缩历史 |
| 窗口 tokens | `aiContextWindowTokens` | 32000 | 上下文窗口大小 |
| 思考工具栏 | `aiThinkingToolbarEnabled` | true | 显示思考过程卡片 |
| 阅读工具模式 | `aiReadToolMode` | "safe" | 阅读场景工具范围（all/safe/custom） |
| 工具版本 | `aiEnabledToolNamesVersion` | 12 | 当前工具定义版本 |

### 5.2 生成服务配置

| 配置项 | PreferKey | 说明 |
|---|---|---|
| 图片 Provider 列表 | `aiImageProviderList` | JSON 序列化 |
| 当前图片 Provider | `aiCurrentImageProviderId` | — |
| 视频 Provider 列表 | `aiVideoProviderList` | JSON 序列化 |
| 当前视频 Provider | `aiCurrentVideoProviderId` | — |
| 音频 Provider 列表 | `aiAudioProviderList` | JSON 序列化 |
| 当前音频 Provider | `aiCurrentAudioProviderId` | — |

### 5.3 文本净化配置

| 配置项 | PreferKey | 默认值 | 说明 |
|---|---|---|---|
| 净化开关 | `aiSanitizeEnabled` | false | — |
| 触发方式 | `aiSanitizeTrigger` | "manual" | manual/auto |
| 净化强度 | `aiSanitizeIntensity` | "standard" | light/standard/aggressive |
| 自定义提示词 | `aiSanitizeCustomPrompt` | "" | 覆盖默认提示词 |

### 5.4 朗读配置

| 配置项 | PreferKey | 默认值 | 说明 |
|---|---|---|---|
| 角色功能 | `aiReadAloudRoleEnabled` | false | 朗读角色分配 |
| 角色模型 | `aiReadAloudRoleModelId` | null | — |
| 配乐功能 | `aiReadAloudBgmEnabled` | false | 朗读配乐 |
| 配乐音量 | `aiReadAloudBgmVolume` | 0.3 | 0.0~1.0 |
| 音效音量 | `aiReadAloudSfxVolume` | 0.5 | 0.0~1.0 |
| 自动创建角色 | `aiReadAloudAutoCreateCharacters` | false | — |
| 自动生成头像 | `aiReadAloudAutoCreateAvatar` | false | — |

---

## 六、开发指南

### 6.1 添加新的 AI 生成类型

1. **实体** — 在 `data/entities/` 中创建实体类，定义表名、字段和索引
2. **DAO** — 在 `data/dao/` 中创建 DAO 接口
3. **Service** — 在 `help/ai/` 中创建服务类，实现 submit/poll/download 流程
4. **GalleryManager** — 在 `help/ai/` 中创建画廊管理器
5. **Tool** — 在 `help/ai/` 中创建工具定义，注册到 `AiToolRegistry`
6. **Provider 配置** — 在 `AiConfigModels.kt` 中添加 Provider 配置数据类
7. **Provider 编辑** — 创建 ProviderEditActivity
8. **设置入口** — 在 `AiConfigFragment` 中添加入口
9. **数据库迁移** — 递增版本号，在 `DatabaseMigrations.kt` 中定义迁移

### 6.2 数据库迁移规范

- 每次修改实体必须递增 `AppDatabase.version`
- 迁移必须覆盖所有中间版本，确保任意版本用户都能升级
- 实体中的 `@Index(name=...)` 必须与迁移 DDL 的索引名称完全一致
- 实体中的 `@ColumnInfo(defaultValue=...)` 必须与迁移 DDL 的 DEFAULT 值完全一致
- 禁止使用 `fallbackToDestructiveMigration()`，会清空用户数据

### 6.3 Compose 策略设置

所有 ComposeView 必须在 `setContent` 之前设置 `ViewCompositionStrategy`：

```kotlin
composeView.setViewCompositionStrategy(
    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
)
composeView.setContent { /* ... */ }
```

### 6.4 协程与异常处理

- `CancellationException` 必须重新抛出，不可在 `runCatching` 中吞掉
- 数据库 IO 操作使用 `withContext(Dispatchers.IO)`
- 长时间异步任务（视频/音频生成）使用 `AiGenTaskManager` + `AiGenPoller` 模式
- `LaunchedEffect` 中的轮询循环必须在异常时不递增 `refreshKey`，防止无限循环

### 6.5 配置数据模型

所有 AI 配置数据类定义在 `ui/main/ai/AiConfigModels.kt`：

- `AiProviderConfig` — 聊天 Provider（id, name, baseUrl, apiKey, models）
- `AiModelConfig` — 模型配置（id, name, apiModelName, maxTokens, temperature, reasoningEffort）
- `AiImageProviderConfig` — 图片 Provider（id, name, baseUrl, apiKey, model, enableHd, enableInpaint）
- `AiVideoProviderConfig` — 视频 Provider
- `AiAudioProviderConfig` — 音频 Provider
- `AiMcpServerConfig` — MCP 服务器（id, name, endpoint, apiKey, enabled）
- `AiSkillConfig` — Skill 配置（id, name, providerName, filePath, enabled）
- `AiChatCompanionConfig` — 角色助手（id, name, avatar, systemPrompt, modelId, skillIds, mcpServerIds, worldBookId）
- `AiWorldBookConfig` — 世界书（id, name, enabled, entries）

### 6.6 关键文件索引

| 层级 | 路径 | 说明 |
|---|---|---|
| 数据库 | `data/AppDatabase.kt` | Room 数据库定义，版本 121 |
| 迁移 | `data/DatabaseMigrations.kt` | 所有迁移定义 |
| 配置 | `help/config/AppConfig.kt` | 所有 AI PreferKey 和 getter/setter |
| 实体 | `data/entities/Ai*.kt` | 20 个 AI 实体 |
| DAO | `data/dao/Ai*.kt` | 16 个 AI DAO |
| 服务 | `help/ai/Ai*Service.kt` | 聊天/图片/视频/音频/净化/摘要服务 |
| 工具 | `help/ai/Ai*Tool.kt` | 15 组工具定义 |
| 运行时 | `help/ai/AiAgentRuntime.kt` | Agent 工具循环 |
| 注册 | `help/ai/AiToolRegistry.kt` | 工具注册中心 |
| MCP | `help/ai/AiMcpClient.kt` | MCP 协议客户端 |
| UI | `ui/main/ai/` | 聊天、画廊、对话框 |
| 设置 | `ui/config/AiConfigFragment.kt` | AI 配置页面 |
| 阅读 | `ui/book/read/ReadAi*.kt` | 阅读 AI 面板 |