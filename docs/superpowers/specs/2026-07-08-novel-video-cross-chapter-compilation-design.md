# 跨章节拼接成整部视频 — 设计文档

- 日期：2026-07-08
- 子项目：E（novel-video 子系统的增量功能）
- 状态：待实现
- 前置依赖：ArcReel 移植（子项目 A，P0-P7 完成）+ 调度强化（子项目 B+，P0-P6 完成）+ 第五轮审查（R10-R15 全部修复，CI 绿）

## 1. 背景与动机

当前一个 `NovelVideoJob` 对应一段章节范围（单章或多章），Stage 7 `mergeCompletedSegments` 把该 job 内的 `NovelVideoSegment.localVideoPath` 列表合并为 `novel_video/<jobId>/merged.mp4`。用户想把「整本书」或「一个故事弧」做成一部可分享/可回看的完整视频时，需手动拼接多个 job 的产物。

spec `2026-07-05-novel-to-video-in-legado-design.md` 第 11 节明确「默认每章独立合并，跨章拼接作为可选项后续再考虑」。本子项目实现该可选项。

## 2. 目标与非目标

### 目标
1. 用户在任务中心多选 ≥2 个同书已完成 job，一键拼成一部完整视频。
2. 拼接产物作为一等公民持久化（落盘 + 落库），可独立回看/分享/删除。
3. 复用现有 `VideoMuxer.merge`（无损 remux），不引入转码子系统。
4. 源 job 删除不影响已产出的整部视频文件（编译产物自包含）。

### 非目标（YAGNI）
- 不做转码统一格式：源 mp4 格式（mime/宽高）不一致时直接拒绝，给出可操作错误。
- 不做 compilation 的 resume / 队列化：本地无损 mux、无网络、秒级完成，走 UI 直起。
- 不做跨书拼接：所选 job 必须同 `bookUrl`。
- 不做视频编辑/裁剪/转场/字幕烧录（属「视频剪辑编辑器」另一子项目）。
- 不做 compilation 的版本管理 / 增量更新（源 job 重新生成后需手动重新编译）。

## 3. 方案选择

| 方案 | 描述 | 评价 |
|---|---|---|
| A. 一次性导出（无持久化） | 多选 job → `VideoMuxer.merge` → 临时文件 → 立即分享/存相册 | 最小但无作品记录，想再看要重拼，与「整部视频=作品」心智不符。**否决**。 |
| **B. 持久化 Compilation 实体（采用）** | 新增 `NovelVideoCompilation` 表，多选 job → 产出独立 mp4 落盘 + 落库 → 任务中心新增「整部视频」入口可回看/分享/删除 | 作品一等公民，符合用户预期，边界清晰，复用 `VideoMuxer`。**采用**。 |
| C. 扩展 Job 为跨章模式 | 给 `NovelVideoParams` 加 `crossChapterMerge` 开关 | 与现有 Job=章节范围模型冲突，动调度/resume/segments 全链路，过于侵入。**否决**。 |

## 4. 数据模型

### 4.1 新增实体 `NovelVideoCompilation`

```kotlin
@Parcelize
@Entity(
    tableName = "novel_video_compilations",
    indices = [
        Index("bookUrl"),
        Index("createdAt")
    ]
)
data class NovelVideoCompilation(
    @PrimaryKey
    val id: String,                              // "nvc_<uuid>"
    @ColumnInfo(defaultValue = "")
    val title: String = "",                      // 用户可见标题，默认 "<bookName> 整部视频 <createdAt>"
    @ColumnInfo(defaultValue = "")
    val bookUrl: String = "",
    @ColumnInfo(defaultValue = "")
    val bookName: String = "",
    @ColumnInfo(defaultValue = "[]")
    val sourceJobIdsJson: String = "[]",         // ["nv_x","nv_y"] 按 chapterStartIndex 升序
    @ColumnInfo
    val outputPath: String? = null,              // novel_video/compilations/<id>/full.mp4
    @ColumnInfo
    val totalDurationMs: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val segmentCount: Int = 0,                   // = sourceJobIds.size
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable
```

**设计要点**：
- **不建 FK 到 `novel_video_jobs`**：源 job 可被独立删除，已产出的整部视频文件自包含不受影响（`sourceJobIdsJson` 仅作来源记录，删除源 job 不会级联删除 compilation）。
- **`sourceJobIdsJson` 按章节顺序存储**：编译时按 `chapterStartIndex` 升序排列后存档，回看/审计时可还原拼接顺序。
- **`outputPath` 指向独立目录** `novel_video/compilations/<nvc_id>/full.mp4`，与 job 级 `novel_video/<jobId>/` 目录隔离，源 job 删除清目录不影响 compilation 文件。

### 4.2 Room 迁移 v111 → v112

`AppDatabase.kt` `version = 111` → `112`，`DatabaseMigrations.kt` 新增：

```kotlin
val MIGRATION_111_112 = object : Migration(111, 112) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `novel_video_compilations` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL DEFAULT '',
                `bookUrl` TEXT NOT NULL DEFAULT '',
                `bookName` TEXT NOT NULL DEFAULT '',
                `sourceJobIdsJson` TEXT NOT NULL DEFAULT '[]',
                `outputPath` TEXT,
                `totalDurationMs` INTEGER,
                `segmentCount` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL DEFAULT 0,
                `updatedAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_novel_video_compilations_bookUrl` ON `novel_video_compilations` (`bookUrl`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_novel_video_compilations_createdAt` ON `novel_video_compilations` (`createdAt`)")
    }
}
```

`AppDatabase.kt` 的 `@Database(entities = [...])` 列表新增 `NovelVideoCompilation::class`，`version = 112`。schema 文件遵循项目惯例不纳入版本控制（git 跟踪止于 109.json，110/111/112 均未提交，CI 不校验 schema），本地由 KSP 编译自动生成。

## 5. 编译操作

### 5.1 `NovelVideoCompiler`（新增 object）

位置：`app/src/main/java/io/legado/app/help/ai/NovelVideoCompiler.kt`

```kotlin
object NovelVideoCompiler {

    sealed class CompileResult {
        data class Success(val compilation: NovelVideoCompilation) : CompileResult()
        data class Failed(val reason: String) : CompileResult()
    }

    /**
     * 编译多个已完成 job 的产物为一部整部视频。
     *
     * 步骤：
     * 1. 校验：jobIds 非空 ≥2、所选 job 全部 COMPLETED、outputPath 文件存在、同 bookUrl。
     * 2. 排序：按 chapterStartIndex 升序（保证章节顺序正确）。
     * 3. 格式一致性预检：调 VideoMuxer.checkFormatConsistency（提为 public）。
     * 4. 复用 VideoMuxer.merge 产出 novel_video/compilations/<nvc_id>/full.mp4。
     * 5. 落库 NovelVideoCompilation。
     *
     * 本地无损 mux、无网络、秒级完成，不进 GenerationQueue 调度。
     *
     * @return [CompileResult]；失败时不留半成品文件。
     */
    suspend fun compile(
        jobIds: List<String>,
        title: String? = null,
        dao: NovelVideoDao = appDb.novelVideoDao,
        mediaMerger: suspend (inputPaths: List<String>, outputPath: String) -> MediaMergeOutcome = ::defaultMediaMerger,
        outputDir: File? = null
    ): CompileResult
}
```

`mediaMerger` / `outputDir` 为测试注入参数（默认值兼容生产调用）：`mediaMerger` 隔离 `VideoMuxer.merge`（Robolectric 下无法产出真实 mp4），`outputDir` 隔离 `appCtx.filesDir`（普通 `Application` 下未初始化）。另定义 `sealed class MediaMergeOutcome { FormatInconsistent, Merged, Failed }` 把 `checkFormatConsistency` 与 `merge` 两步合一，便于测试注入与错误归类。

### 5.2 校验规则（按顺序短路）

| # | 校验 | 失败错误信息 |
|---|---|---|
| 1 | `jobIds.size >= 2` | "至少选择 2 个任务才能拼成整部视频" |
| 2 | 所选 job 全部存在 | "任务 <id> 不存在" |
| 3 | 所选 job `status == COMPLETED` | "任务 <bookName> 尚未完成（当前状态 <status>），仅可拼接已完成任务" |
| 4 | 所选 job `outputPath` 文件存在 | "任务 <bookName> 的合并产物文件缺失，请重新生成" |
| 5 | 所选 job 同 `bookUrl` | "只能拼接同一本书的任务（涉及 <bookA> 与 <bookB>）" |
| 6 | 格式一致性（mime + 宽高） | "任务 <X> 与 <Y> 的视频格式不一致（<mimeA/wA×hA> vs <mimeB/wB×hB>），无法无损拼接，请用相同 provider/分辨率重新生成" |

校验通过后按 `chapterStartIndex` 升序排序源 job（同 index 按 `createdAt` 兜底，理论上不会同 index）。

> 注：`VideoMuxer.checkFormatConsistency` 原始返回的错误信息以「第 N 段」「首段」「首个文件」索引描述（不含 job 标识）。编译层在调用前已建立 `index → job` 映射，对原始错误信息做包装：用正则把「第 N 段」替换为 `jobs[N-1]` 的 `bookName + chapterRange`，「首段」「首个文件」替换为 `jobs[0]` 的标识，精确到具体不一致的两章，再返回 `CompileResult.Failed`。这是 §5.3 提为 public 的核心动机——让编译层能在 `merge` 前拿到一致性错误并附加 job 上下文。

### 5.3 `VideoMuxer.checkFormatConsistency` 提为 public

当前 `VideoMuxer.checkFormatConsistency` 是 `private`（仅 `merge` 内部调用）。`NovelVideoCompiler` 需在 `merge` 前做预检以给出更精确的错误信息（`merge` 失败时返回的 `Failed.message` 不含 job 标识，不便用户定位是哪两章不一致）。

改动：`private fun checkFormatConsistency` → `fun checkFormatConsistency`（签名不变，返回 `String?` 错误描述或 `null` 表示一致）。`merge` 内部调用点不变。

> 实现补充（P2 收尾）：核心比对逻辑抽为 `internal fun compareTrackKeys(first: TrackKey, cur: TrackKey, curIndex: Int): String?` 纯函数 + `internal data class TrackKey(mime, width, height)`。Robolectric 下 `ShadowMediaExtractor` 不解析 mp4（`trackCount` 恒 0），`checkFormatConsistency` 的完整路径无法在 JVM 单测覆盖；拆分纯函数后，三分支（一致 / mime 不同 / 分辨率不同）可单测真覆盖。

### 5.4 文件路径与清理

- 产出路径：`<appCtx.filesDir>/novel_video/compilations/<nvc_id>/full.mp4`
- 编译失败：删除已创建的 `full.mp4` 和空 `<nvc_id>/` 目录，不留半成品。
- 删除 compilation（UI 操作）：`<nvc_id>/` 目录递归删除 + DB 行删除。

## 6. DAO 接口

`NovelVideoDao.kt` 新增 compilation 相关方法：

```kotlin
// ===== NovelVideoCompilation =====

@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun insertCompilation(c: NovelVideoCompilation)

@Query("SELECT * FROM novel_video_compilations ORDER BY createdAt DESC")
fun getCompilationsFlow(): Flow<List<NovelVideoCompilation>>

@Query("SELECT * FROM novel_video_compilations ORDER BY createdAt DESC")
suspend fun getCompilations(): List<NovelVideoCompilation>

@Query("SELECT * FROM novel_video_compilations WHERE id = :id LIMIT 1")
suspend fun getCompilation(id: String): NovelVideoCompilation?

@Query("DELETE FROM novel_video_compilations WHERE id = :id")
suspend fun deleteCompilation(id: String)

@Query("SELECT * FROM novel_video_compilations WHERE bookUrl = :bookUrl ORDER BY createdAt DESC")
fun getCompilationsByBookFlow(bookUrl: String): Flow<List<NovelVideoCompilation>>
```

## 7. UI 设计

### 7.1 任务中心多选拼接入口

`NovelVideoTaskCenterScreen.kt` 已完成 Tab 增加：
- 长按 JobCard 进入多选模式（顶部 ActionBar 显示选中数 + 「拼成整部视频」按钮 + 取消）。
- 多选模式下继续点按切换选中态。
- 「拼成整部视频」按钮启用条件：选中 ≥2。点击时若选中 job 不全同 `bookUrl`，toast 提示「只能拼接同一本书的任务」并阻止拼接（混合 bookUrl 仍允许点击以便给出反馈）。
- 点击按钮 → 弹进度对话框（`CircularProgressIndicator` + "正在拼接整部视频..."）→ `lifecycleScope.launch` 调 `NovelVideoCompiler.compile` → 成功后清空选中 + 跳转整部视频 Tab（跳 Tab 即暗示成功，未额外 toast）；失败弹 AlertDialog 显示错误原因。

### 7.2 整部视频入口

`NovelVideoTaskCenterScreen.kt` 顶部增加「整部视频」入口（Tab 或顶部按钮，二选一由实现期定，推荐 Tab）：
- 列表展示 `NovelVideoCompilation`（标题 / bookName / 时长 / 段数 / createdAt）。
- 点击进入复用现有 `VideoPlayer` 或 `NovelVideoJobDetailScreen` 的播放能力（直接 `openOutputVideo` 派发 Intent）。
- 每项三个操作：打开 / 分享 / 删除（删除需二次确认，清文件 + DB 行）。

### 7.3 ViewModel 扩展

`NovelVideoTaskCenterViewModel.kt` 新增：
- `_compilations: MutableStateFlow<List<NovelVideoCompilation>>` + `init` 收集 `getCompilationsFlow()`。
- `compileJobs(jobIds: List<String>, title: String?)`：调 `NovelVideoCompiler.compile`，返回 `Result<NovelVideoCompilation>`（用 `runCatching` 包 `CompileResult` 转 `Result`）。
- `deleteCompilation(id: String)`：`withContext(IO)` + `NovelVideoJobOps.mutex.withLock`（复用现有互斥锁避免与 job 删除并发）→ 删文件 + `deleteCompilation`。

## 8. 错误处理

| 场景 | 处理 |
|---|---|
| 校验失败（任一规则） | 返回 `CompileResult.Failed(reason)`，UI 弹 AlertDialog 显示可操作错误，不留文件 |
| `VideoMuxer.merge` 失败 | 返回 `CompileResult.Failed(result.message)`，清理已创建的产出目录 |
| 磁盘满 / IO 异常 | `merge` 内部 `runCatching` 捕获，转 `Failed`；编译层再清理目录 |
| 协程取消（用户退出） | `CancellationException` 向上传播，编译层 `finally` 清理半成品目录 |
| Room 写入失败（极罕见） | 编译层 `runCatching` 包 `insertCompilation`，失败时清理已产出的 mp4 文件并返回 `Failed` |

## 9. 测试计划

### 9.1 单元测试 `NovelVideoCompilerTest.kt`

| 测试 | 覆盖点 |
|---|---|
| `compileRejectsLessThanTwoJobs` | jobIds.size=1 → Failed("至少选择 2 个") |
| `compileRejectsNonCompletedJob` | 含 GENERATING/PARTIAL_FAILED job → Failed |
| `compileRejectsMissingOutputFile` | job COMPLETED 但 outputPath 文件被删 → Failed |
| `compileRejectsMixedBooks` | 两个 job bookUrl 不同 → Failed |
| `compileRejectsInconsistentFormat` | 两段 mp4 分辨率不同 → Failed，错误含两个 job 标识 |
| `compileSucceedsAndPersists` | 两段格式一致的 COMPLETED job → Success，DB 有行，文件存在 |
| `compileOrdersByChapterStartIndex` | 传入乱序 jobIds，产出顺序按 chapterStartIndex |
| `compileIsIdempotent` | 同组 job 重复编译 → 覆盖产出（新 nvc_id），旧的需用户手动删 |
| `compileCleansUpOnFailure` | 格式不一致时 `<nvc_id>/` 目录不残留 |

### 9.2 迁移测试

迁移正确性通过 in-memory Room 集成测试验证（`NovelVideoDaoRobolectricTest` / `GenerationQueueDaoTest` 的 in-memory DB 含 `NovelVideoCompilation` 实体，可插入/查询 compilations 表）。未使用 `MigrationTestHelper`（项目惯例不提交 schema json，`MigrationTestHelper` 依赖 schema 文件做一致性校验，不适用）；migration SQL 为简单 `CREATE TABLE` + 2 索引，in-memory DB 覆盖足够。

### 9.3 VideoMuxer 可见性变更回归

`VideoMuxer.checkFormatConsistency` 提为 public 后，现有 `merge` 调用点不变；补一个直接调用 `checkFormatConsistency` 的测试确认 public 入口可用。

## 10. 不在范围内（YAGNI 重申）

- 转码统一格式（一致性失败即拒，不引入 FFmpeg/ MediaCodec 编码器）。
- compilation 的 resume / 队列化（本地秒级，不需调度）。
- 跨书拼接。
- 视频编辑/裁剪/转场/字幕/BGM（属另一子项目）。
- compilation 版本管理 / 源 job 变更后自动重编译。
- compilation 的封面图（复用首源 job 的 `coverPath` 作为缩略图，由 UI 层读取，不在编译层生成）。

## 11. 验收标准

1. 任务中心已完成 Tab 可多选 ≥2 个同书 COMPLETED job，「拼成整部视频」按钮可触发编译。
2. 编译成功后整部视频入口可见新条目，可打开播放、分享、删除。
3. 格式不一致时给出明确错误，指明是哪两章不一致，不留半成品文件。
4. 删除源 job 不影响已产出的整部视频文件可播放。
5. Room v111→v112 迁移在全新安装和升级两条路径下均正常。
6. 单元测试全绿，CI 通过。
7. `VideoMuxer.checkFormatConsistency` 提为 public 后，现有 `merge` 行为不变（回归测试通过）。

## 12. 风险与对策

| 风险 | 对策 |
|---|---|
| 不同 provider 生成的 mp4 格式不一致（如 Ark 1080p vs Kling 720p）→ 用户跨 provider 拼接频繁失败 | 错误信息明确指引「用相同 provider/分辨率重新生成」；未来可在 job 创建时记录 provider/resolution，UI 多选时预先过滤同 provider 的 job |
| 大量章节拼接产物文件占磁盘 | UI 展示 compilation 时长 + 文件大小；删除入口显眼 |
| MediaMuxer 对某些 mp4 容器（如 HEVC + 特定 profile）remux 兼容性问题 | 复用现有 `VideoMuxer.merge` 已验证的 `checkFormatConsistency` + 时间戳重基准逻辑；若 merge 失败，错误透传给用户 |
| Room schema 导出文件 `112.json` 未提交导致 CI 失败 | 项目惯例 schema 不纳入版本控制（110/111 均未提交，CI 不校验 schema）；本地由 KSP 编译自动生成，迁移正确性由 in-memory Room 集成测试覆盖 |
