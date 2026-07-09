# Novel-video 项目化工作台 — 设计文档（子项目 C）

- 日期：2026-07-09
- 子项目：C（UI 工作台）
- 状态：待实现
- 前置依赖：ArcReel 移植（子项目 A，完成）+ 流水线编排（子项目 B，完成）+ 调度强化（子项目 B+，完成）+ 跨章节拼接（子项目 E，完成）+ 整体回归审查（完成，CI 绿，HEAD=95a83ccf）

## 1. 背景与动机

现有 novel-video UI 是「任务管理工具」形态：任务中心 4 Tab（进行中/已完成/失败/整部视频）→ 任务详情（概要+输出+角色+分镜段）→ 剧本审阅 → 配置。功能完整，但有结构性缺口：

- **缺聚合视角**：任务以「章节范围」为单位，用户想把一整本书做成视频时，要在 4 个 Tab 间反复跳，看不到「这本书整体进度、还差哪些章节」。子项目 E 的「整部视频」与「任务」割裂——整部视频只是已完成任务的产物，没有「这本书的章节覆盖了多少」的视角。
- **新建任务绕路**：用户在浏览这本书进度时想「为下一段章节新建任务」，要退出书语境→任务中心 FAB→重新选书→选范围。

本子项目引入「书/作品」为一等公民（视图层，不建新表），以书为中心聚合章节进度+任务+整部视频，把「做一本书」的完整心智闭环到一处。

## 2. 目标与非目标

### 目标
1. 新增「书架」一级入口：列出所有有 novel-video 任务/产物的书，每书显示章节覆盖进度。
2. 新增「书详情」页：章节覆盖图（色块网格）+ 该书的任务列表 + 该书的整部视频，一处看清「这本书做到哪了」。
3. 书详情页可预填 bookUrl 直接新建任务（融入书语境）。
4. 保留原任务中心 4 Tab（跨书按状态管理仍有价值）。

### 非目标（YAGNI）
- 不新建 `Project`/`Book` 表：书是 `bookUrl` 的聚合视图，数据全在现有 `novel_video_jobs`/`novel_video_compilations`/`books`/`book_chapters` 表，零 schema 变更。
- 不做子项目 B（创作过程增强：分镜缩略图/逐段重生）——数据字段（`imageUrl`/`localVideoPath`）已就绪，但属另一增量，本子项目先做聚合骨架。
- 不做子项目 D（Project/Shot/Character 领域模型重构）。
- 不做书的元信息编辑（书名/封面取自 `books` 表，不可在 novel-video 内编辑）。
- 不做跨书聚合/统计看板。
- 不做书架的排序/筛选/搜索（任务量级不大，YAGNI；若后续需要再按子项目 C 管理增强加）。

## 3. 方案选择

| 方案 | 描述 | 评价 |
|---|---|---|
| A1. 书架优先 | 入口换成书架，原 4 Tab 下沉到书详情 | 聚合最强但破坏「跨书按状态看任务」的老习惯。**否决**。 |
| **A2. 双入口（采用）** | 顶部「书架」+「任务」两个一级入口并存；书架点书→书详情；任务保留原 4 Tab | 不破坏老习惯，聚合视角作为新增，边界清晰。**采用**。 |
| A3. 书架内嵌 | 书架作为第 5 Tab | 5 Tab 太挤，且书架与「整部视频」Tab 职责重叠。**否决**。 |

**章节覆盖图呈现**：

| 方案 | 描述 | 评价 |
|---|---|---|
| **B1. 色块网格（采用）** | 每章一色块，颜色表状态，120 章一屏看完 | 「一眼看完整本书」最直接，是 A 方案核心价值。**采用**。 |
| B2. 进度条+区间列表 | 总进度条 + 每 job 区间条形 | 信息全但 job 多时长，区间信息下方任务列表已有，重复。**否决**。 |
| B3. 混合 | 网格总览 + 区间列表详情 | 两套呈现过度设计。**否决**。 |

## 4. 信息架构

```
NovelVideoTaskCenterActivity（宿主不变）
└─ 顶部一级入口切换（新增）
   ├─ 📚 书架（新增）
   │   └─ 点书卡片 → NovelVideoBookDetailActivity（新增）
   │       ├─ 概要：bookName · X/Y 章 · 进度条
   │       ├─ 章节覆盖图（色块网格，B1）
   │       ├─ 该书的任务列表（按 bookUrl 过滤，复用 JobCard）
   │       ├─ 该书的整部视频列表（按 bookUrl 过滤，复用 CompilationCard）
   │       └─ FAB：新建任务（预填 bookUrl，复用 NovelVideoJobConfigSheet）
   └─ 📋 任务（原 4 Tab 不变）
       ├─ 进行中 / 已完成 / 失败 / 整部视频
       └─ (保留现有全部行为，含子项目 E 多选拼接)
```

**导航约定**：
- 书架是 `NovelVideoTaskCenterActivity` 内的一个视图态（顶部 SegmentedButton 切换），不新建 Activity 宿主。切换态由 `rememberSaveable` 持久化。
- 书详情是独立 `NovelVideoBookDetailActivity`（新 Activity，因为它是深层页面，有独立返回栈、独立 VM 生命周期更清晰）。
- 任务 Tab 的 4 个分桶完全不动，包括子项目 E 的多选拼接入口。

## 5. 数据层（零 schema 变更）

### 5.1 现有可用（无需改动）

- `NovelVideoJob.bookUrl`/`bookName`/`chapterStartIndex`/`chapterEndIndex`/`chapterTitlesJson` — 聚合视图的数据源。
- `NovelVideoDao.getJobsByBook(bookUrl)` — 该书所有任务（suspend 版，书架快照用；书详情页需 Flow 版，见 §7.2 新增）。
- `NovelVideoDao.getCompilationsByBookFlow(bookUrl)` — 该书整部视频 Flow。
- `BookChapterDao.getChapterCount(bookUrl)` — 书的总章节数（算「X/Y 章」）。
- `BookDao.getBookByUrl(bookUrl)` — 取书名/封面。

### 5.2 新增 DAO 查询

书架需列出「所有有 novel-video 任务的 bookUrl 去重列表」。现有 DAO 无此查询，新增：

```kotlin
// NovelVideoDao.kt
@Query("""
    SELECT DISTINCT bookUrl FROM novel_video_jobs
    WHERE bookUrl != ''
    UNION
    SELECT DISTINCT bookUrl FROM novel_video_compilations
    WHERE bookUrl != ''
""")
fun getBookUrlsWithNovelVideoFlow(): Flow<List<String>>
```

`UNION` 保证「任务已删但整部视频还在」的书仍出现在书架（符合 §1「整部视频自包含」的产品语义）。返回 `Flow` 以响应任务/产物的增删。

### 5.3 聚合模型（纯视图，不落库）

书架卡片和书详情概要需要「该书的聚合进度」。定义为纯数据类（运行时从 jobs + chapterCount 计算，不建表）：

```kotlin
data class BookNovelVideoSummary(
    val bookUrl: String,
    val bookName: String,
    val coverPath: String?,          // 取自 Book，可空
    val totalChapters: Int,          // BookChapterDao.getChapterCount，0 表示未知
    val coveredChapterIndices: Set<Int>,  // 所有 job 的 [start,end] 并集
    val completedChapterIndices: Set<Int>, // 状态 COMPLETED 的 job 覆盖的章节
    val failedChapterIndices: Set<Int>,    // FAILED/PARTIAL_FAILED 覆盖的章节
    val runningChapterIndices: Set<Int>,   // RUNNING_STATES 覆盖的章节
    val jobCount: Int,
    val compilationCount: Int
) {
    val coveredCount: Int get() = coveredChapterIndices.size
    val progress: Float get() = if (totalChapters <= 0) 0f else coveredCount.toFloat() / totalChapters
}
```

**章节覆盖算法**：遍历该书的 jobs，对每个 job 把 `[chapterStartIndex, chapterEndIndex]` 展开为整数集，按 job.status 归入对应集合。覆盖并集 = completed ∪ failed ∪ running。

> 边界：`chapterStartIndex`/`chapterEndIndex` 为 -1（异常 job）的忽略，不并入任何集合。`totalChapters=0`（书章节未加载）时进度条显示「未知」，不显示百分比。

## 6. UI 设计

### 6.1 书架页

`NovelVideoBookShelfScreen`（新 Composable，由 `NovelVideoTaskCenterScreen` 在「书架」入口态渲染）：

- 顶部：标题「书架」。
- 列表：`LazyColumn`，每项 `BookShelfCard`。
- 空态：「还没有任何书的视频任务，去「任务」Tab 新建一个吧」。
- 点击卡片 → `onOpenBookDetail(bookUrl, bookName)` → 启动 `NovelVideoBookDetailActivity`。

`BookShelfCard` 结构（对应方案 A 线框图）：

```
┌─────────────────────────────────────┐
│ [封面] 斗破苍穹                      │
│        8/120 章 · 进度 6%            │
│        ▓▓░░░░░░░░░░░░░░░░░░         │
│        3 个任务 · 1 部整部视频        │
└─────────────────────────────────────┘
```

- 封面：`Book.coverImage` 有则加载（复用现有图片加载），无则占位。
- 进度条：`LinearProgressIndicator(progress = summary.progress)`。
- 数字：`coveredCount/totalChapters` + jobCount + compilationCount。

### 6.2 书详情页

`NovelVideoBookDetailActivity`（新 Activity）+ `NovelVideoBookDetailViewModel`（新 VM）+ `NovelVideoBookDetailScreen`（新 Composable）。

**Screen 结构**（LazyColumn）：

1. **概要卡片**：bookName · `coveredCount/totalChapters 章` · 进度条 · jobCount/compilationCount。
2. **章节覆盖图**（B1 色块网格）：见 §6.3。
3. **Section：任务 (N)**：该书的 jobs 列表（`NovelVideoDao.getJobsByBook`），复用 `JobCard`（抽取为 internal 共享组件）。点击→`NovelVideoJobDetailActivity`。
4. **Section：整部视频 (N)**：该书的 compilations（`getCompilationsByBookFlow`），复用 `CompilationCard`。点击播放/分享/删除（复用现有逻辑）。
5. **FAB**：新建任务（预填 bookUrl）。

**TopAppBar**：返回 + bookName + overflow（无额外操作，保持简洁）。

**新建任务融入**：FAB 点击弹 `NovelVideoJobConfigSheet`（复用现有组件），传入 `presetBookUrl`/`presetBookName`。现有 sheet 已支持预填，无需改动。创建成功后 sheet 关闭，书详情页的 Flow 自动刷新（任务列表/覆盖图/概要同步更新）。

### 6.3 章节覆盖图组件

`ChapterCoverageGrid`（新 Composable）：

```
参数：totalChapters: Int, statusByChapter: Map<Int, ChapterStatus>
enum class ChapterStatus { NONE, RUNNING, COMPLETED, FAILED }
```

- `LazyVerticalGrid`（列数自适应，约 8-12 列按屏宽），每格一个色块。
- 色块颜色：NONE=灰色 / RUNNING=蓝 / COMPLETED=绿 / FAILED=红。
- 色块点击：`onChapterClick(chapterIndex)` → 滚动定位到该章所属 job 的卡片（若无 job 则 toast「该章尚未建任务」）。
- 长按色块：显示章节标题 tooltip（`BookChapterDao.getChapterList(bookUrl, idx, idx)` 取标题，惰性加载）。
- 图例：底部一行 `■ 未做 ■ 进行中 ■ 已完成 ■ 失败`。
- `totalChapters=0`：显示「章节信息未加载」占位，不渲染网格。

> 性能：`LazyVerticalGrid` 按屏可视区回收，120 章无压力；`statusByChapter` 是 `Map<Int, ChapterStatus>`，由 VM 从 jobs 预计算（§5.3 算法），网格只读不计算。

### 6.4 共享组件抽取

现有 `JobCard`/`CompilationCard` 定义在 `NovelVideoTaskCenterScreen.kt` 内为 `private`。书详情页要复用，需提升可见性：

- `JobCard` → `internal`（同 module 可见），移到 `NovelVideoComponents.kt`（现有共享组件文件）。
- `CompilationCard` → 同上。
- `StatusBadge`、`formatChapterRange`、`formatTime` → 同上（已被多处用，应集中）。

这是为支撑本子项目的最小重构，不顺手做无关整理。

## 7. ViewModel 设计

### 7.1 `NovelVideoTaskCenterViewModel`（扩展）

新增书架数据：

```kotlin
private val _shelfBooks = MutableStateFlow<List<BookNovelVideoSummary>>(emptyList())
val shelfBooks: StateFlow<List<BookNovelVideoSummary>> = _shelfBooks.asStateFlow()

init {
    // 现有 init 块末尾追加
    viewModelScope.launch {
        appDb.novelVideoDao.getBookUrlsWithNovelVideoFlow().collectLatest { urls ->
            _shelfBooks.value = urls.map { buildSummary(it) }
        }
    }
}

private suspend fun buildSummary(bookUrl: String): BookNovelVideoSummary {
    val book = appDb.bookDao.getBookByUrl(bookUrl)
    val jobs = appDb.novelVideoDao.getJobsByBook(bookUrl)
    val total = runCatching { appDb.bookChapterDao.getChapterCount(bookUrl) }.getOrDefault(0)
    val compilations = appDb.novelVideoDao.getCompilationsByBookFlow(bookUrl).first()
    // 计算 covered/completed/failed/running 章节集合（§5.3 算法）
    ...
    return BookNovelVideoSummary(...)
}
```

> 注：`getCompilationsByBookFlow` 是 Flow，书架只需一次性快照，用 `.first()` 取首值。书详情页才需持续监听。

### 7.2 `NovelVideoBookDetailViewModel`（新增）

```kotlin
class NovelVideoBookDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val _summary = MutableStateFlow<BookNovelVideoSummary?>(null)
    val summary: StateFlow<BookNovelVideoSummary?> = _summary.asStateFlow()

    private val _jobs = MutableStateFlow<List<NovelVideoJob>>(emptyList())
    val jobs: StateFlow<List<NovelVideoJob>> = _jobs.asStateFlow()

    private val _compilations = MutableStateFlow<List<NovelVideoCompilation>>(emptyList())
    val compilations: StateFlow<List<NovelVideoCompilation>> = _compilations.asStateFlow()

    private val _chapterTitles = MutableStateFlow<Map<Int, String>>(emptyMap())
    val chapterTitles: StateFlow<Map<Int, String>> = _chapterTitles.asStateFlow()

    fun load(bookUrl: String) {
        viewModelScope.launch {
            // summary：监听 jobs flow 重算
            appDb.novelVideoDao.getJobsByBookFlow(bookUrl).collectLatest { jobs ->
                _jobs.value = jobs
                _summary.value = recomputeSummary(bookUrl, jobs)
            }
        }
        viewModelScope.launch {
            appDb.novelVideoDao.getCompilationsByBookFlow(bookUrl).collectLatest { _compilations.value = it }
        }
    }

    fun loadChapterTitle(bookUrl: String, chapterIndex: Int) { /* 惰性加载单章标题 */ }
    fun createJob(...) { /* 委托，与 TaskCenterVM.createJob 同逻辑；或抽取共享 */ }
    fun deleteJob/deleteCompilation(...) { /* 同 TaskCenterVM */ }
}
```

**`getJobsByBookFlow` 需新增**：现有 `getJobsByBook` 是 `suspend`，无 Flow 版。书详情页要响应任务状态变化实时刷新覆盖图，需加：

```kotlin
@Query("SELECT * FROM novel_video_jobs WHERE bookUrl = :bookUrl ORDER BY chapterStartIndex ASC")
fun getJobsByBookFlow(bookUrl: String): Flow<List<NovelVideoJob>>
```

### 7.3 创建/删除任务的共享

`createJob`/`deleteJob`/`deleteCompilation`/`compileJobs` 在 `TaskCenterVM` 和 `BookDetailVM` 都要用。为避免复制，抽取到 `NovelVideoJobOps`（现有 object，已持有 `mutex`）：

```kotlin
object NovelVideoJobOps {
    val mutex = Mutex()
    suspend fun createJob(book, start, end, chapters, params): String { /* 从 TaskCenterVM 移入 */ }
    suspend fun deleteJob(jobId: String) { /* 移入 */ }
    suspend fun deleteCompilation(id: String) { /* 移入 */ }
    suspend fun retryJob(jobId: String) { /* 移入 */ }
    suspend fun cancelJob(jobId: String) { /* 移入 */ }
    suspend fun compileJobs(jobIds, title): Result<NovelVideoCompilation> { /* 移入 */ }
}
```

两个 VM 改为薄委托调 `NovelVideoJobOps`。这是支撑本子项目的最小去重，不动签名语义。

## 8. 导航与入口

### 8.1 顶部一级入口切换

`NovelVideoTaskCenterScreen` 顶部增加 `SingleChoiceSegmentedButtonRow`（书架 / 任务）。选中态 `rememberSaveable` 持久化（避免旋转丢失）。

- 选「书架」：渲染 `NovelVideoBookShelfScreen`。
- 选「任务」：渲染现有 4 Tab 内容（原 Scaffold body 逻辑不动）。

TopAppBar 标题随选中态切换：「书架」/「任务中心」。FAB 行为：书架态（任务中心内的视图态）无 FAB——新建任务在书详情页（独立 Activity）内；任务态（任务中心内的视图态）保留原 FAB。

### 8.2 书详情 Activity

```kotlin
class NovelVideoBookDetailActivity : VMBaseActivity<..., NovelVideoBookDetailViewModel>() {
    companion object {
        const val EXTRA_BOOK_URL = "bookUrl"
        const val EXTRA_BOOK_NAME = "bookName"
        fun newIntent(context: Context, bookUrl: String, bookName: String): Intent
    }
}
```

入口：书架卡片点击 → `onOpenBookDetail` → `startActivity(NovelVideoBookDetailActivity.newIntent(...))`。

### 8.3 AndroidManifest

注册 `NovelVideoBookDetailActivity`（新 Activity 需声明）。

## 9. 错误处理

| 场景 | 处理 |
|---|---|
| 书架 bookUrl 对应的 Book 已删（书库移除） | `getBookByUrl` 返回 null，bookName 回退为 bookUrl，coverPath=null，仍显示（整部视频自包含语义要求书架保留） |
| `getChapterCount` 返回 0（章节未加载） | 进度条显示「未知」，不显示百分比，覆盖图显示「章节信息未加载」 |
| job 的 chapterStartIndex/EndIndex 异常（-1） | 覆盖算法忽略该 job，不并入任何章节集合 |
| 书详情页 job 状态变化 | Flow 自动刷新覆盖图/概要/任务列表，无需手动 reload |
| 新建任务失败 | `NovelVideoJobConfigSheet` 现有错误处理不变 |

## 10. 测试计划

### 10.1 单元测试 `BookNovelVideoSummaryTest`（纯逻辑）

| 测试 | 覆盖点 |
|---|---|
| `coverageMergesOverlappingJobRanges` | 两 job 区间重叠（1-3, 3-5）→ covered={1,2,3,4,5} |
| `coverageSplitsByStatus` | COMPLETED job(1-2) + FAILED job(3-3) + RUNNING job(4-5) → 三集合正确 |
| `progressComputesAgainstTotalChapters` | covered=8, total=120 → progress≈0.067 |
| `progressZeroWhenTotalUnknown` | total=0 → progress=0，不除零 |
| `ignoresInvalidChapterIndices` | job chapterStart=-1 → 不并入集合 |
| `emptyJobsYieldsEmptyCoverage` | 无 job → 所有集合空，progress=0 |

覆盖算法是纯函数（`fun computeCoverage(jobs, totalChapters): BookNovelVideoSummary`），可纯 JVM 单测，不依赖 Android/Room。

### 10.2 DAO 测试

`getBookUrlsWithNovelVideoFlow` UNION 正确性：在 in-memory Room 插入 job（bookA）+ compilation（bookB）→ Flow 收到 [bookA, bookB]；删 job 后 bookA 仍在（因 compilation？否，bookA 无 compilation 应消失；bookB 无 job 但有 compilation 应保留）。

### 10.3 VM 测试（薄，评估后可选）

VM 主要是 Flow 收集 + 委托 `NovelVideoJobOps`，核心逻辑在 `computeCoverage`（已单测）。VM 测试成本高（硬编码 appDb），按回归审查 P1-2 同理评估，**默认不做**，除非实现中发现非平凡逻辑。

### 10.4 回归

- 现有 4 Tab 行为不变（任务入口态完全保留原逻辑）。
- 子项目 E 多选拼接不受影响（仍在任务入口态的已完成 Tab）。
- `NovelVideoJobConfigSheet` 复用不变（预填 bookUrl 是现有能力）。

## 11. 验收标准

1. 任务中心顶部有「书架」/「任务」切换；选「任务」时与改造前完全一致（回归）。
2. 「书架」列出所有有任务/整部视频的书，每书显示章节覆盖进度条 + 任务数 + 整部视频数。
3. 点书卡片进入书详情页：章节覆盖图（色块网格）+ 任务列表 + 整部视频列表 + FAB 新建任务。
4. 章节覆盖图：颜色正确区分未做/进行中/已完成/失败；点色块定位该章 job；120 章一屏可看完。
5. 书详情页 FAB 新建任务预填 bookUrl，创建成功后覆盖图/列表实时刷新。
6. 任务/整部视频的删除、重试、取消、播放、分享在书详情页可用且与任务中心行为一致。
7. 书删除（书库移除）后书架条目仍保留（若该 bookUrl 有 compilation），不崩溃。
8. 无 schema 变更、无迁移、现有单元测试全绿、CI 通过。

## 12. 风险与对策

| 风险 | 对策 |
|---|---|
| `getBookUrlsWithNovelVideoFlow` 的 UNION 在大表上性能 | novel_video_jobs/compilations 表预期量级小（百级），DISTINCT bookUrl 走 `Index("bookUrl")`，无性能问题 |
| 书架 summary 计算对每本书查 chapterCount + jobs + compilations，书多时 N 次查询 | 书架量级预期 ≤ 几十本；若未来需优化，可加单 SQL 聚合查询。当前 YAGNI |
| 色块网格在超大书（几千章）下渲染卡顿 | `LazyVerticalGrid` 回收可视区；几千章仍可接受。超大道士文（万章）极端情况后续按需虚拟化，当前 YAGNI |
| `NovelVideoJobOps` 抽取改动现有 VM，引入回归 | 抽取保持方法签名语义不变；现有 4 Tab 行为有回归测试覆盖；CI 兜底 |
| 共享组件提升可见性（private→internal）可能被误用 | `internal` 限同 module，novel-video 包内复用合理；不加额外约束 |

## 13. 不在范围内（YAGNI 重申）

- 分镜段缩略图/逐段重生/对比（子项目 B 创作增强，另起）。
- Project/Shot/Character 领域模型（子项目 D）。
- 书架排序/筛选/搜索/统计看板。
- 书的元信息编辑。
- 跨书聚合。
- 书详情页的多选拼接入口（多选拼接仍只在任务中心的已完成 Tab，避免两处入口语义重叠）。
