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
