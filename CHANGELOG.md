**2026/07/06**

* 小说转视频：完成四轮代码审查，共 97 项修复 + 68 个测试用例，CI 全绿
* 第四轮高风险（N1-N5）：修复第三轮 R6 引入的 retryJob 失效回归（新增 `updateJobStatusForRetry` 反向守卫）；通知改 VISIBILITY_PRIVATE + 移除 errorMessage 拼接保护阅读隐私；VideoMuxer 多段合并前校验格式一致性；隐私声明补充 AI 数据流告知
* 第四轮中风险（N6-N11）：R3 熔断改用 `updateSegmentStatus` 不递增 retryCount 避免 UI 重试死循环；AiVideoService 错误消息脱敏（不再含完整响应体）；NovelVideoPromptBuilder 移除 8000 字符硬截断；confirmScreenplay 补空场景校验
* 第三轮高风险（R1-R10）：合并失败不再误标 COMPLETED；retryCount 熔断防 API 配额浪费；PAUSED 死状态防护；start() 短路窗口修复；retry/cancel 加 Mutex 串行化；ConfigSheet 旋转保留输入；WakeLock 防 Doze 停滞；shouldStopOnTaskRemoved/onTimeout 重写
* 第三轮中风险（M1-M13）：deleteJob 清理磁盘文件；50% 失败阈值改 >=；retry 智能重置保留 imageUrl；pickNextJob 跳过 PENDING_REVIEW；bind() 幂等；通知刷新 try/catch；startForegroundServiceCompat
* 第三轮低风险：JobCard 改 combinedClickable；formatChapterRange 缓存 GSON 解析
* 详见 spec Section 13「实施完成摘要」
* 高风险修复（D1-D6）：NovelVideo 终态写入全部改用条件 UPDATE（`WHERE status NOT IN ('completed','failed','partial_failed','cancelled')`），消除 TOCTOU 窗口，避免并发的 CANCELLED 被覆写为 COMPLETED/FAILED
* 高风险修复（H1-H4）：`AiToolExecutor` / `AiChatService` / `AiReadAloudRoleService` / `AiReadAloudBgmService` 在所有 `catch(Throwable)` / `runCatching{}.getOrElse{}` 入口显式重抛 `CancellationException`，恢复协程取消语义
* 中风险修复（M1-M4）：`sceneDurationSeconds` 与 `maxCharacters` 边界 coerceIn 统一；`ScreenplayDraft` 两套 fromJson 合一；`markSegmentFailed` 默认参数改用常量
* 系统性修复：中间态写入（DRAFTING/GENERATING/MERGING）改用条件部分更新，杜绝「用户已取消的任务被标记为已完成」的取消信号丢失链
* 低风险修复（L1-L3）：DAO SQL 字面量与 Kotlin 常量一致性测试；`@ColumnInfo(defaultValue)` 改用 `const val`；`AiVideoTaskPoller.Stage` 常量撞名注释
* 详见 spec Section 13「实施完成摘要」

**2026/07/05**

* 新增「小说转视频」功能：8 阶段流水线（章节正文 → LLM 分镜剧本 → 角色三视图 → 逐场景生图/生视频 → MediaMuxer 合并 → 产物入库），复刻 director_ai 方案，复用 Legado AI 基建（AiChatService / AiImageService）
* 新增 `NovelVideoJob`/`NovelVideoSegment`/`NovelVideoCharacterSheet` 实体 + DAO + DB Migration 109→110
* 新增 `NovelVideoGenerator`（流水线编排）+ `NovelVideoPromptBuilder`（提示词构造/净化/LLM 改写）+ `NovelVideoScreenplayParser`（4 策略 JSON 提取）+ `NovelVideoChapterLoader`
* 新增 `AiVideoService` + `AiVideoTaskPoller`（文生视频 API + 异步轮询）+ `VideoMuxer`（MediaMuxer 无损 remux）
* 新增 `NovelVideoService` 前台服务（长任务宿主，复刻 CacheBookService）+ EventBus 通知
* 新增 Compose UI：任务中心（`NovelVideoManageActivity`）、任务详情、剧本审阅（可编辑/重新生成/确认）、参数配置 BottomSheet、视频 Provider 管理
* 入口：`BookInfoActivity`「生成视频」按钮 + 阅读菜单「播放视频」按钮（`PLAY_CHAPTER_VIDEO`）
* 产物双轨：写入 `BookChapter.resourceUrl`（阅读页可播放）+ 任务中心独立管理
* 健壮性：Stage 5/6 各 3 次重试（前 2 次敏感词软替换，第 3 次 LLM 改写回退 sanitize）；剧本生成 3 次重试带反馈；断点续传
* 设计规格详见 `docs/superpowers/specs/2026-07-05-novel-to-video-in-legado-design.md`

**2022/10/02**

* 更新cronet: 106.0.5249.79
* 正文选择菜单朗读按钮长按可切换朗读选择内容和从选择开始处一直朗读
* 源编辑输入框设置最大行数12,在行数特别多的时候更容易滚动到其它输入
* 修复某些情况下无法搜索到标题的bug，净化规则较多的可能会降低搜索速度 by Xwite
* 修复文件类书源换源后阅读bug by Xwite
* Cronet 支持DnsHttpsSvcb by g2s20150909
* 修复web进度同步问题 by 821938089
* 启用混淆以减小app大小 有bug请带日志反馈
* 其它一些优化
