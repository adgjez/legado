# NovelVideo 调度层强化实现计划（子项目 B+，方案 A 全量移植）

- **日期**：2026-07-07
- **对应 spec**：`docs/superpowers/specs/2026-07-07-novel-video-scheduling-hardening-design.md`
- **执行方式**：分 7 阶段，每阶段末提交 + 推 CI，CI 绿才进下一阶段（review checkpoint）
- **总规模**：~6 新文件 + ~8 文件改动 + ~40 单测用例
- **ArcReel 参考**：`lib/generation_queue.py`（382 行）、`lib/generation_worker.py`（1048 行）、`lib/task_failure.py`、`lib/db/models/task.py`、`lib/db/repositories/task_repo.py`

## 阶段总览

| 阶段 | 内容 | 可验证产物 | 依赖 |
|---|---|---|---|
| P0 | 数据模型迁移 + TaskFailure | Room v110→v111 + 失败码单测 | — |
| P1 | GenerationQueue facade | enqueue/claim/markSucceeded/markFailed/markCancelled 单测 | P0 |
| P2 | CapacityTable + SlotTable | 并发上限 + 池满黑名单单测 | P0 |
| P3 | GenerationWorker 主循环 + orphan handler | claim→run→mark 流程 + orphan 恢复单测 | P1,P2 |
| P4 | resume 路径接入 NovelVideoGenerator | Stage 6 provider_job_id 持久化 + resumeVideo 调用 | P3 |
| P5 | OrphanRecoveryWorker + BootReceiver + Manifest | WorkManager 15min 周期 + 开机触发 | P3 |
| P6 | NovelVideoService 改用 GenerationWorker + 清理 | 多 worker 并发 + CI 绿 | P3,P4,P5 |

---

## P0 · 数据模型迁移 + TaskFailure

**目标**：Room schema v110→v111 加列 + 结构化失败码工具类。

**任务**：

1. `NovelVideoJob.kt` 新增列：
   - `workerId: String?`（认领 worker 标识，单进程固定 "default"）
   - `workerHeartbeatAt: Long?`（worker 最近心跳 epoch ms，null=未被认领）
   - `providerJobId: String?`（提交后 provider 端任务 ID，resume 用）
   - `providerId: String?`（入队时派生的 video provider ID）
   - `attempts: Int = 0`（尝试次数）
   - `availableAt: Long = 0`（下次可调度时间，回队延迟用）
2. `NovelVideoSegment.kt` 新增列：
   - `providerJobId: String?`（Stage 6 视频任务 provider 端 ID）
   - `providerId: String?`（Stage 6 使用的 video provider）
3. `DatabaseMigrations.kt` 加 `MIGRATION_110_111`：ALTER TABLE 加 6+2 列（参考现有 `MIGRATION_109_110` 行 44-131）
4. `AppDatabase.kt` version 110→111，注册 migration
5. 新建 `help/ai/scheduling/TaskFailure.kt`（移植 `task_failure.py`）：
   - `encodeFailure(code: String, params: Map<String, Any>? = null): String` → `[code]` 或 `[code] {sorted-json}`
   - `renderFailure(message: String): String` → 按 App locale 渲染中文文案（未识别格式原样透传）
   - 失败码常量：`RESUME_UNSUPPORTED_PROVIDER`、`RESTART_LOST_NO_JOB_ID`、`RESUME_EXPIRED_DETAIL`、`RESTART_LOST_IMAGE`、`PROVIDER_UNSUPPORTED_MEDIA`、`RESTART_LOST_AUDIO`
6. 新建 `help/ai/scheduling/NonResumableProviders.kt`：
   - `NON_RESUMABLE_VIDEO_PROVIDERS = setOf("grok", "vidu")`（移植 `generation_worker.py:43-56`）

**验证**：
- `./gradlew :app:compileDebugKotlin` 通过
- `TaskFailureTest`：encode 无参/有参/未知 code 抛错；render 已知 code/未知格式原样/历史裸文本
- `AppDatabaseMigrationTest`（如现有有 Robolectric 测试基础设施）：v110→v111 加列成功，老数据默认值正确

**提交**：`feat(ai/scheduling): P0 数据模型迁移 v110→v111 + TaskFailure 失败码`

---

## P1 · GenerationQueue facade

**目标**：移植 `generation_queue.py` + `task_repo.py` 的队列操作，Room DAO 实现 enqueue/claim/mark 系列。

**任务**：

1. `NovelVideoDao.kt` 新增方法：
   - `enqueueJob(job)` — 现有 `insertJob` 已可用（REPLACE 策略），无需新增
   - `claimNextJob(workerId, now, leaseTtlMs, excludeProviders: List<String>): Int` — 原子认领（移植 `task_repo.py:213-247` 的 SELECT + UPDATE WHERE status='queued' 模式）
     - SQL：`UPDATE novel_video_jobs SET status='generating', workerId=:workerId, workerHeartbeatAt=:now, attempts=attempts+1, updatedAt=:now WHERE id = (SELECT id FROM novel_video_jobs WHERE status IN ('drafting','screenplay_confirmed','generating','merging') AND (providerId IS NULL OR providerId NOT IN (:exclude)) AND availableAt <= :now ORDER BY createdAt ASC LIMIT 1)`
     - 返回受影响行数（0=无可认领 job）
   - `renewLease(jobId, workerId, now): Int` — `UPDATE ... SET workerHeartbeatAt=:now WHERE id=:jobId AND workerId=:workerId AND status NOT IN (终态)` 返回行数（0=已失 lease 或已终态）
   - `releaseLease(jobId)` — `UPDATE ... SET workerId=NULL, workerHeartbeatAt=NULL WHERE id=:jobId`
   - `markJobSucceededIfRunning(jobId, outputPath, totalDurationMs, now): Int` — WHERE status='generating'/'merging'
   - `markJobFailedIfRunning(jobId, errorMessage, now): Int` — WHERE status NOT IN 终态
   - `markJobCancelledIfActive(jobId, now): Int` — WHERE status IN ('drafting','generating','merging','screenplay_confirmed')
   - `requeueJob(jobId, availableAt, now): Int` — `UPDATE ... SET status='queued'(用 drafting), workerId=NULL, workerHeartbeatAt=NULL, availableAt=:availableAt WHERE id=:jobId AND status='generating'`
   - `getOrphanJobs(now, heartbeatTimeoutMs): List<NovelVideoJob>` — `WHERE status IN ('generating','merging') AND (workerHeartbeatAt IS NULL OR workerHeartbeatAt < :now - :timeout)`
   - `getQueuedJobsCount(): Int`
2. 新建 `help/ai/scheduling/GenerationQueue.kt`（移植 `generation_queue.py` facade）：
   - `object GenerationQueue`（单例，依赖 `appDb.novelVideoDao`）
   - `suspend fun claimNextJob(workerId: String, excludeProviders: List<String>): NovelVideoJob?` — 调 DAO claimNextJob，affected>0 则 getJob 返回
   - `suspend fun renewLease(jobId, workerId): Boolean`
   - `suspend fun releaseLease(jobId)`
   - `suspend fun markSucceeded/markFailed/markCancelled/requeue`
   - `suspend fun findOrphans(heartbeatTimeoutMs): List<NovelVideoJob>`
   - 常量：`LEASE_TTL_MS = 30_000L`（30s，比 ArcReel 10s 宽松，Android 后台限制）、`HEARTBEAT_INTERVAL_MS = 10_000L`（10s）

**验证**：
- `GenerationQueueTest`（Robolectric in-memory Room）：
  - claimNextJob 无 job 返回 null
  - claimNextJob 有 job 返回 + status→generating + workerId/heartbeat 写入
  - 双 claim 同一 job 第二个 affected=0
  - renewLease workerId 不匹配返回 0
  - markSucceededIfRunning 竞态（已被 cancelled）返回 0
  - requeue 后 job 可被重新 claim
  - getOrphanJobs 心跳超时判定

**提交**：`feat(ai/scheduling): P1 GenerationQueue facade（enqueue/claim/mark/orphan）`

---

## P2 · CapacityTable + SlotTable

**目标**：移植 `generation_worker.py:89-315` 的并发控制双表。

**任务**：

1. 新建 `help/ai/scheduling/CapacityTable.kt`（移植 `generation_worker.py:89-205`）：
   - `class CapacityTable`
   - `_limits: MutableMap<Pair<String, String>, Int>`（providerId × mediaType → 上限）
   - `_defaults: Map<String, Int>`（`mapOf("video" to 1, "image" to 2, "audio" to 2)`）
   - `get(providerId, mediaType): Int` — 三态：已知>0 / 已知=0(不支持) / 未知→默认
   - `loadFromConfig()` — 读 `AppConfig.aiVideoProviderConfigList` 的 `defaultParamsJson` 字段 `video_max_workers`/`image_max_workers`，回退默认
   - `replace(newLimits)` — 配置变更时整表换
2. 新建 `help/ai/scheduling/SlotTable.kt`（移植 `generation_worker.py:221-315`）：
   - `class SlotTable`
   - `_slots: MutableMap<Pair<String, String>, MutableMap<String, Job>>`（provider × media → jobId → Job）
   - `register(providerId, mediaType, jobId, job: Job)`
   - `release(providerId, mediaType, jobId)` — 清空 bucket 时一并删除（保证 `occupiedProviders` 永不返回空 provider）
   - `hasRoom(providerId, mediaType, capacity): Boolean`
   - `occupiedProviders(mediaType): Set<String>`
   - `findByJobId(jobId): Pair<String, String>?`（cancel 用）
   - `activeJobIds(): Set<String>`
3. 新建 `help/ai/scheduling/PoolFullCalculator.kt`（移植 `generation_worker.py:489-505`）：
   - `fun calculate(mediaType, slots: SlotTable, capacity: CapacityTable): Set<String>` — 返回池满的 provider 集合，供 claim SQL 黑名单

**验证**：
- `CapacityTableTest`：三态语义；loadFromConfig 读 defaultParamsJson；replace 整表换
- `SlotTableTest`：register/hasRoom/release；空 bucket 不残留；findByJobId；occupiedProviders 不返回空 provider
- `PoolFullCalculatorTest`：池满判定；capacity=0 的 provider 不算池满（是 unsupported）

**提交**：`feat(ai/scheduling): P2 CapacityTable + SlotTable（并发控制双表）`

---

## P3 · GenerationWorker 主循环 + orphan handler

**目标**：移植 `generation_worker.py` 的 worker 主循环 + orphan 检测/恢复 + 0-rows-cancelled 协议。

**任务**：

1. 新建 `help/ai/scheduling/GenerationWorker.kt`（移植 `generation_worker.py:433-1048`）：
   - `class GenerationWorker(workerId: String, leaseTtlMs, heartbeatMs)`
   - `private val capacity = CapacityTable()`、`private val slots = SlotTable()`
   - `private var orphanHandledOnce = false`
   - `suspend fun runLoop(scope: CoroutineScope, isCancelled: () -> Boolean)`：
     - while (!isCancelled())：
       - `renewLease`（acquire_or_renew 模式，失败则 sleep 等）
       - `drainFinishedTasks()`（pop done inflight Job，0-rows 兜底 mark_cancelled）
       - 首次持 lease → `handleOrphanTasksOnStart()`
       - `claimTasks()`：for media in (video, image)：while hasRoom：claim → slots.register + scope.async { processTask }
       - sleep（claimed=50ms / idle=heartbeatMs）
   - `private suspend fun processTask(job: NovelVideoJob)`（移植 `generation_worker.py:650-694`）：
     - try: `NovelVideoGenerator.generate(job.id, isCancelled)` 
     - catch CancellationException: `withContext(NonCancellable) { markCancelled }`; throw
     - catch Throwable: `rows = withContext(NonCancellable) { markFailed(jobId, encodeFailure(...)) }`; if rows==0 markCancelled（0-rows 协议）
     - finally: slots.release + releaseLease
   - `private suspend fun handleOrphanTasksOnStart()`（移植 `generation_worker.py:800-945`）：
     - `orphans = findOrphans(heartbeatTimeoutMs)`
     - 对每个 orphan：
       - status='generating'/'merging' → 调 `NovelVideoGenerator.resumeJob(job.id)`（P4 实现）
       - 异常分类：CancellationException→markCancelled；ResumeExpiredError→markFailed[resume_expired_detail]；NotImplementedError→markFailed[resume_unsupported_detail]；其他→markFailed
   - `suspend fun requestCancel(jobId)` — slots.findByJobId + job.cancel()
2. `NovelVideoGenerator.kt` 加 `resumeJob(jobId)` 占位（P4 实现）：先抛 NotImplementedError

**验证**：
- `GenerationWorkerTest`（Robolectric + mock Generator）：
  - runLoop 无 job 时 idle sleep
  - claim→processTask→markSucceeded 完整流程
  - processTask 抛异常→markFailed；0-rows（已被 cancel）→markCancelled
  - CancellationException→markCancelled + 重抛
  - handleOrphanTasksOnStart 调 resumeJob
  - 池满时 claim 不返回该 provider 的 job

**提交**：`feat(ai/scheduling): P3 GenerationWorker 主循环 + orphan handler`

---

## P4 · resume 路径接入 NovelVideoGenerator

**目标**：Stage 6 提交后持久化 provider_job_id；崩溃恢复时调 `VideoBackend.resumeVideo` 而非重新提交。

**任务**：

1. `NovelVideoGenerator.processSegment` Stage 6 改造（`NovelVideoGenerator.kt:655-716`）：
   - 提交前从 `AiVideoProviderConfig` 取 `providerId`（type 字段）
   - `AiVideoService.generate` 返回 `VideoGenerationResult` 后，若 `result.taskId` 非空：
     - `appDb.novelVideoDao.updateSegmentProviderJobId(segment.id, result.taskId, providerId)`
     - `JobIdStore.save(providerId, result.taskId, model, promptDigest)`
   - 完成后 `JobIdStore.clear(providerId, taskId)`（防 store 膨胀）
2. `NovelVideoDao.kt` 加：
   - `updateSegmentProviderJobId(segmentId, providerJobId, providerId)`
   - `clearSegmentProviderJobId(segmentId)`
3. `NovelVideoGenerator.resumeJob(jobId)` 实现（替换 P3 占位）：
   - 读 job + segments
   - 对每个 `VIDEO_GENERATING` segment：
     - `providerId ∈ NON_RESUMABLE_VIDEO_PROVIDERS` → `markSegmentFailed(segmentId, FAILED, encodeFailure(RESUME_UNSUPPORTED_PROVIDER))`
     - `providerJobId == null` → `markSegmentFailed(..., encodeFailure(RESTART_LOST_NO_JOB_ID))`
     - 有 `providerJobId` → 
       - 构造 `VideoGenerationRequest`（从 segment 字段 + job.paramsJson）
       - `VideoBackendRegistry.byConfig(providerConfig).resumeVideo(providerJobId, request)`
       - 成功 → 下载 + `updateSegmentVideo` + `clearSegmentProviderJobId`
       - `ResumeExpiredError` → `markSegmentFailed(..., encodeFailure(RESUME_EXPIRED_DETAIL))`
       - `NotImplementedError` → `markSegmentFailed(..., encodeFailure(RESUME_UNSUPPORTED_PROVIDER))`
   - 对每个 `IMAGE_GENERATING` segment → `markSegmentFailed(..., encodeFailure(RESTART_LOST_IMAGE))`（image 无 resume，重生成由后续 stage 处理）
   - 调 `generate(jobId, isCancelled)` 继续未完成 stage
4. `AiVideoService.generate` 返回的 `VideoGenerationResult.taskId` 确认非空（子项目 A 已实现，backend 返回 taskId）

**验证**：
- `NovelVideoGeneratorResumeTest`（Robolectric + mock backend）：
  - Stage 6 提交后 segment.providerJobId 持久化
  - resumeJob 对 VIDEO_GENERATING + providerJobId → 调 resumeVideo
  - resumeJob 对 VIDEO_GENERATING + grok/vidu → markFailed[resume_unsupported_provider]
  - resumeJob 对 VIDEO_GENERATING + null providerJobId → markFailed[restart_lost_no_job_id]
  - resumeJob 对 ResumeExpiredError → markFailed[resume_expired_detail]
  - resumeJob 对 IMAGE_GENERATING → markFailed[restart_lost_image]
  - JobIdStore save/clear 配对调用

**提交**：`feat(ai/scheduling): P4 resume 路径接入（provider_job_id 持久化 + resumeVideo）`

---

## P5 · OrphanRecoveryWorker + BootReceiver + Manifest

**目标**：WorkManager 15min 周期巡检 + 开机触发 + AndroidManifest 权限/组件声明。

**任务**：

1. `app/build.gradle` 加依赖：`androidx.work:work-runtime-ktx:2.9.0`（或项目已有的 work 版本）
2. 新建 `help/ai/scheduling/OrphanRecoveryWorker.kt`：
   - `class OrphanRecoveryWorker(ctx, params) : CoroutineWorker(ctx, params)`
   - `override suspend fun doWork(): Result`：
     - `orphans = appDb.novelVideoDao.getOrphanJobs(now, heartbeatTimeoutMs = 5 * 60 * 1000L)`
     - 若 orphans 非空 → `NovelVideoService.start(applicationContext)` 触发前台服务（服务启动时 handleOrphanTasksOnStart 会处理）
     - return Result.success()
3. 新建 `help/ai/scheduling/NovelVideoScheduler.kt`：
   - `object NovelVideoScheduler`：
     - `fun schedulePeriodicOrphanSweep(context)`：
       - `WorkManager.getInstance(context).enqueueUniquePeriodicWork("novel_video_orphan_sweep", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<OrphanRecoveryWorker>(15, TimeUnit.MINUTES).build())`
     - `fun cancelPeriodicOrphanSweep(context)`
4. 新建 `receiver/BootReceiver.kt`：
   - `class BootReceiver : BroadcastReceiver()`
   - `onReceive`：若 `intent.action == ACTION_BOOT_COMPLETED` → `NovelVideoService.start(context)`
5. `AndroidManifest.xml`：
   - `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />`
   - `<receiver android:name=".receiver.BootReceiver" android:exported="true"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter></receiver>`
6. `App.kt` 或 `Application.onCreate`：调 `NovelVideoScheduler.schedulePeriodicOrphanSweep(this)`（App 启动即注册周期任务）

**验证**：
- `OrphanRecoveryWorkerTest`（Robolectric）：
  - 无孤儿时 doWork 返回 success 不启动服务
  - 有孤儿时启动 NovelVideoService（mock 验证 start 调用）
- `BootReceiverTest`：ACTION_BOOT_COMPLETED → start service
- 编译通过 + Manifest 合并无冲突

**提交**：`feat(ai/scheduling): P5 OrphanRecoveryWorker + BootReceiver + WorkManager 巡检`

---

## P6 · NovelVideoService 改用 GenerationWorker + 清理

**目标**：NovelVideoService 从单 pipelineJob 串行改为 N 个 GenerationWorker 并发；清理老代码；CI 全绿。

**任务**：

1. `NovelVideoService.kt` 重构：
   - 删 `pipelineJob: Job?`（`NovelVideoService.kt:106`）
   - 删 `pickNextJob`（`NovelVideoService.kt:258-266`）
   - 改 `ensurePipelineRunning`（`NovelVideoService.kt:229-248`）：
     - 启动 N 个 worker 协程（默认 N=2，可配），每个协程跑 `GenerationWorker("worker-$i", leaseTtlMs, heartbeatMs).runLoop(scope, isCancelled)`
     - 用 `CoroutineScope(SupervisorJob() + Dispatchers.Default)` 管理多 worker
   - `cancelCurrentJob` 改为调 `GenerationWorker.requestCancel(currentJobId)`（`NovelVideoService.kt:97-103, 164-170`）
   - 保留 `PARTIAL_WAKE_LOCK`、`onTimeout`、`shouldStopOnTaskRemoved`
   - `onDestroy` / `stop`：cancel scope + releaseLease for all active jobs
2. `NovelVideoTaskCenterViewModel.kt` / `NovelVideoJobDetailViewModel.kt`：
   - 统一 `jobOpMutex` 为共享单例（修复缺口 1.4.4 双 ViewModel 并发）：
     - 新建 `object NovelVideoJobOps { val mutex = Mutex() }`
     - 两个 ViewModel 都用 `NovelVideoJobOps.mutex`
   - `retryJob` 的 segment 重置加条件更新（`updateSegmentStatusIfNotTerminal`）
3. `NovelVideoDao.kt` 加 `updateSegmentStatusIfNotTerminal`（WHERE status NOT IN ('video_completed','failed')）
4. 清理：删 `JobIdStore.kt` 中未被调用的空架子代码（若 P4 后 JobIdStore 仍有用则保留）
5. `AppConfig.normalizeAiVideoProviders`：加 `video_max_workers`/`image_max_workers` 字段白名单

**验证**：
- `NovelVideoServiceMultiWorkerTest`（Robolectric）：
  - 两个 worker 可同时认领不同 job
  - 一个 worker 崩溃后 lease 释放，另一个可接管
  - cancelCurrentJob 调 requestCancel
- `NovelVideoJobOpsMutexTest`：双 ViewModel 并发 retry+cancel 不覆写
- `./gradlew :app:compileDebugKotlin` 通过
- `./gradlew :app:assembleDebug` 通过
- CI unit-test job 通过

**提交**：`refactor(ai/scheduling): P6 NovelVideoService 多 worker 并发 + ViewModel 互斥修复`

---

## 风险与回退

- 每阶段独立提交，任一阶段 CI 红可 `git revert <commit>` 回退
- P0 Room migration 是破坏性变更，若 migration 失败可回退到 v110（但需用户重装 app，单用户无灰度）
- P4 resume 路径依赖子项目 A 的 `VideoBackend.resumeVideo`，若某 backend 的 resume 实现有 bug 需单独修
- P6 多 worker 改动大，若 OOM 或竞态严重可回退到单 worker（保留 GenerationWorker 但 N=1）
- WorkManager 周期任务在 Doze 下可能延迟（Android 限制），需配合前台服务自检兜底

## 验收（对应 spec 第 7 节）

1. Stage 6 视频任务提交后 `providerJobId` 持久化到 DB ✓（P4）
2. Stage 6 中途崩溃后恢复时优先调 `resumeVideo` 而非重新提交 ✓（P4）
3. `providerId ∈ {grok, vidu}` 的 segment 崩溃后标 failed 不重提交 ✓（P4）
4. `ResumeExpiredError` 正确标 failed 不无限重试 ✓（P4）
5. CI unit-test 通过 ✓（P6）
6. 多 job 可并行（受 CapacityTable 限制）✓（P6）
7. lease 过期后 job 可被其他 worker 认领 ✓（P3+P6）
8. 结构化失败码可被 UI 解析 ✓（P0 TaskFailure）
9. WorkManager 周期任务 15 min 扫一次孤儿 ✓（P5）
10. BootReceiver 开机触发服务重启 ✓（P5）

## ArcReel 源码行号对照

| 组件 | ArcReel 文件 | 行号 |
|---|---|---|
| 状态机常量 | `generation_queue.py` | 58-62 |
| WorkerLease 表 | `db/models/task.py` | 70-76 |
| Task 表 schema | `db/models/task.py` | 13-53 |
| claim SQL | `db/repositories/task_repo.py` | 213-247 |
| acquire_or_renew_lease | `db/repositories/task_repo.py` | 960-1002 |
| orphan 检测 | `db/repositories/task_repo.py` | 698-703 |
| CapacityTable | `generation_worker.py` | 89-205 |
| SlotTable | `generation_worker.py` | 221-315 |
| pool_full_providers | `generation_worker.py` | 489-505 |
| worker 主循环 | `generation_worker.py` | 433-487 |
| process_task + 0-rows 协议 | `generation_worker.py` | 650-694 |
| orphan handler | `generation_worker.py` | 800-945 |
| NON_RESUMABLE_PROVIDERS | `generation_worker.py` | 43-56 |
| resume dispatcher | `generation_worker.py` | 947-1048 |
| task_failure.encode | `task_failure.py` | 42-54 |
