# 实现计划：novel-video 项目化工作台（子项目 C）

- 对应 spec：`docs/superpowers/specs/2026-07-09-novel-video-project-workbench-design.md`
- 创建：2026-07-09
- 前置 HEAD：`5dbc31d`（spec 提交）
- 策略：按 spec §5→§6→§7→§8 顺序，数据层→共享组件→VM→UI→Activity→测试。每阶段独立 commit，跑现有测试确保不回归。

## Task 0：基线核验

**步骤**：
1. 确认当前分支、HEAD、工作树干净
2. 跑现有 novel-video 相关单测，记录基线绿

**验证**：`./gradlew :app:testDebugUnitTest --tests "io.legado.app.help.ai.*NovelVideo*" --tests "io.legado.app.help.ai.scheduling.*"` 通过（或记录基线状态）

---

## Task 1：数据层 — 新增 DAO 查询

**步骤**：
1. `NovelVideoDao.kt` 新增 `getBookUrlsWithNovelVideoFlow(): Flow<List<String>>`（spec §5.2 UNION 查询）
2. `NovelVideoDao.kt` 新增 `getJobsByBookFlow(bookUrl): Flow<List<NovelVideoJob>>`（spec §7.2，按 chapterStartIndex ASC）

**验证**：编译通过；不破坏现有 DAO 测试

---

## Task 2：聚合模型 + 覆盖算法（纯函数）

**步骤**：
1. 新建 `app/src/main/java/io/legado/app/help/ai/BookNovelVideoSummary.kt`
2. 定义 `data class BookNovelVideoSummary`（spec §5.3 字段）
3. 定义 `enum class ChapterStatus { NONE, RUNNING, COMPLETED, FAILED }`
4. 实现纯函数 `fun computeCoverage(bookUrl, bookName, coverPath, totalChapters, jobs, compilationCount): BookNovelVideoSummary`（spec §5.3 算法：遍历 jobs 展开 [start,end]，按 status 归集；忽略 chapterStart=-1）
5. 在 summary 上加 `statusByChapter(): Map<Int, ChapterStatus>` 派生属性（供 ChapterCoverageGrid 用）

**验证**：编译通过

---

## Task 3：覆盖算法单测

**步骤**：
1. 新建 `app/src/test/java/io/legado/app/help/ai/BookNovelVideoSummaryTest.kt`
2. 实现 spec §10.1 的 6 个测试：
   - coverageMergesOverlappingJobRanges
   - coverageSplitsByStatus
   - progressComputesAgainstTotalChapters
   - progressZeroWhenTotalUnknown
   - ignoresInvalidChapterIndices
   - emptyJobsYieldsEmptyCoverage

**验证**：`./gradlew :app:testDebugUnitTest --tests "io.legado.app.help.ai.BookNovelVideoSummaryTest"` 全绿

---

## Task 4：DAO UNION 查询测试

**步骤**：
1. 找现有 in-memory Room 测试基座（如 `NovelVideoDaoRobolectricTest` 或 `GenerationQueueDaoTest` 的 in-memory DB）
2. 加测试：插 job(bookA) + compilation(bookB) → flow 收到 [bookA, bookB]；删 job → bookA 消失、bookB 保留
3. 加测试：`getJobsByBookFlow` 返回按 chapterStartIndex ASC

**验证**：新测试全绿；现有 DAO 测试不回归

---

## Task 5：抽取 NovelVideoJobOps 共享方法

**步骤**：
1. `NovelVideoJobOps.kt`（现有 object，已持 `mutex`）新增 suspend 方法：`createJob`/`deleteJob`/`deleteCompilation`/`retryJob`/`cancelJob`/`compileJobs`（从 `NovelVideoTaskCenterViewModel` 移入，签名语义不变）
2. `NovelVideoTaskCenterViewModel` 改为薄委托调 `NovelVideoJobOps`（保留 `loadChapters`/`openCompilation`/`shareCompilation`/服务控制等 UI 相关方法）
3. 注意 `createJob` 需 `Application` 上下文调 `NovelVideoService.start`——通过参数传入或用 `appCtx`

**验证**：编译通过；现有 `NovelVideoServiceMultiWorkerTest` 等不回归；`./gradlew :app:testDebugUnitTest --tests "io.legado.app.help.ai.scheduling.*"` 绿

---

## Task 6：共享组件提升可见性

**步骤**：
1. `NovelVideoComponents.kt`（现有共享文件）新增/移入：`JobCard`、`CompilationCard`、`StatusBadge`、`formatChapterRange`、`formatTime`、`formatDuration`（从 `NovelVideoTaskCenterScreen.kt` 和 `NovelVideoJobDetailScreen.kt` 的 private 定义提升为 `internal`）
2. 原 `private` 定义处改为引用共享版本，删除重复定义
3. `novelVideoCardShape` 确认可见（应已 internal）

**验证**：编译通过；UI 行为不变（回归靠现有测试 + Task 11 手动回归）

---

## Task 7：书架 UI 组件

**步骤**：
1. `NovelVideoComponents.kt` 新增 `BookShelfCard(summary: BookNovelVideoSummary, onClick: () -> Unit)` Composable（spec §6.1 结构：封面+书名+进度条+任务数/整部视频数）
2. 封面加载复用项目现有图片加载方式（先查 `Book` 封面如何渲染——可能用 coil/picasso，对齐现有 BookCard）

**验证**：编译通过

---

## Task 8：章节覆盖图组件

**步骤**：
1. `NovelVideoComponents.kt` 新增 `ChapterCoverageGrid(totalChapters: Int, statusByChapter: Map<Int, ChapterStatus>, onChapterClick: (Int) -> Unit, onChapterLongClick: (Int) -> Unit)` Composable（spec §6.3）
2. `LazyVerticalGrid`，列数自适应（`GridCells.Adaptive(28.dp)` 左右）
3. 色块颜色映射 NONE/RUNNING/COMPLETED/FAILED
4. 底部图例
5. `totalChapters=0` 时显示「章节信息未加载」占位

**验证**：编译通过

---

## Task 9：书详情 ViewModel

**步骤**：
1. 新建 `NovelVideoBookDetailViewModel.kt`（spec §7.2）
2. `load(bookUrl)`：collect `getJobsByBookFlow` → 重算 summary（调 `computeCoverage`）；collect `getCompilationsByBookFlow`
3. `loadChapterTitle(bookUrl, idx)`：惰性加载单章标题到 `_chapterTitles` map
4. `createJob`/`deleteJob`/`deleteCompilation`/`retryJob`/`cancelJob` 委托 `NovelVideoJobOps`
5. `openCompilation`/`shareCompilation` 复用（可放 JobOps 或 VM 内，与 TaskCenterVM 一致）

**验证**：编译通过

---

## Task 10：书详情 Screen + Activity

**步骤**：
1. 新建 `NovelVideoBookDetailScreen.kt`（spec §6.2 结构：概要卡 + ChapterCoverageGrid + 任务 Section + 整部视频 Section + FAB）
2. FAB 弹 `NovelVideoJobConfigSheet`（复用，传 presetBookUrl/presetBookName）
3. 色块点击：定位该章所属 job 卡片（LazyColumn scrollTo）或 toast「该章尚未建任务」
4. 色块长按：loadChapterTitle + 显示标题
5. 新建 `NovelVideoBookDetailActivity.kt`（VMBaseActivity，EXTRA_BOOK_URL/EXTRA_BOOK_NAME）
6. `AndroidManifest.xml` 注册 Activity

**验证**：编译通过；APK 能装

---

## Task 11：任务中心双入口切换

**步骤**：
1. `NovelVideoTaskCenterScreen.kt` 顶部加 `SingleChoiceSegmentedButtonRow`（书架/任务）（spec §8.1）
2. 选中态 `rememberSaveable` 持久化
3. 选「书架」→ 渲染 `NovelVideoBookShelfScreen`（LazyColumn of BookShelfCard，空态文案）
4. 选「任务」→ 现有 4 Tab 逻辑完全不动
5. 书架态 TopAppBar 标题「书架」，无 FAB；任务态标题「任务中心」，保留 FAB
6. `NovelVideoTaskCenterViewModel` 新增 `shelfBooks: StateFlow<List<BookNovelVideoSummary>>`（spec §7.1，collect `getBookUrlsWithNovelVideoFlow` → map buildSummary）
7. 书架卡片点击 → `onOpenBookDetail(bookUrl, bookName)` 回调
8. `NovelVideoTaskCenterActivity` 接 `onOpenBookDetail` → 启动 `NovelVideoBookDetailActivity`

**验证**：编译通过；任务态行为回归（4 Tab + 子项目 E 多选拼接）

---

## Task 12：字符串资源

**步骤**：
1. `strings.xml` 新增：书架标题、任务中心标题、空态文案、章节未加载、图例文案、该章无任务 toast 等

**验证**：编译通过

---

## Task 13：整体回归 + 提交

**步骤**：
1. 跑全量 novel-video 单测：`./gradlew :app:testDebugUnitTest --tests "io.legado.app.help.ai.*"`
2. 跑调度测试：`--tests "io.legado.app.help.ai.scheduling.*"`
3. 编译 release/debug APK 不报错
4. lint 检查新 Activity 注册、未用资源等
5. 推送到远程触发 CI

**验证**：CI 绿

---

## 风险与回退

- 若 Task 5（JobOps 抽取）引入回归且难定位：可回退该 commit，书详情 VM 内联实现 createJob/deleteJob（复制而非共享），代价是少量重复但零回归风险。
- 若 Task 10 色块定位 job 的 scrollTo 在 LazyColumn 实现复杂：降级为「点击色块 → toast 显示章节标题 + 该章 job 状态」，不做滚动定位（YAGNI，后续按需加）。
- 若 Task 4 无现成 in-memory DAO 测试基座：改为纯 `computeCoverage` 单测覆盖（Task 3 已做），DAO 查询靠编译 + 手动验证，UNION SQL 简单低风险。
