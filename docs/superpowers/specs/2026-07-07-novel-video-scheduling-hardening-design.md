# NovelVideo 调度层强化设计（子项目 B+）

- **日期**：2026-07-07
- **状态**：已确认（方案 A 全量移植）
- **子项目**：B+（NovelVideoGenerator 调度层强化）—— 子项目 B（流水线编排）的后续强化
- **关联**：
  - 前置 spec `2026-07-05-novel-to-video-in-legado-design.md`（子项目 B，已完成）
  - 前置 spec `2026-07-06-arcreel-backend-port-design.md`（子项目 A，已完成）
  - ArcReel 参考：`lib/generation_queue.py`、`lib/generation_worker.py`、`lib/task_failure.py`、`lib/db/models/task.py`、`lib/db/repositories/task_repo.py`、ADR 0006/0007

## 1. 背景与动机

子项目 B 实现了完整的 8 阶段流水线（`NovelVideoGenerator`）+ 前台服务宿主（`NovelVideoService`）+ Room 持久化（`NovelVideoJob/Segment/CharacterSheet`），并经过四轮深度审查（97 修复 + 68 测试）。流水线功能完整，但**调度层存在结构性缺口**，在 Android 进程易被杀的环境下会导致丢任务与重复扣费。

ArcReel 的 `generation_queue.py` + `generation_worker.py`（共 1430 行）是成熟的 SQLite 持久化队列 + lease + 多 worker 调度 + orphan 恢复方案，直接对照可补齐缺口。

### 1.1 现状架构

```
UI (ViewModel)
  ↓ NovelVideoService.start/stop/cancelCurrentJob (静态 Intent)
NovelVideoService (前台服务)
  ↓ ensurePipelineRunning → while { pickNextJob; runOneJob }
  ↓ 单 pipelineJob 协程，串行
NovelVideoGenerator.generate(jobId, isCancelled)
  ↓ Stage 1-8
  ↓ Stage 5: AiImageService.generate (每次新建外部任务)
  ↓ Stage 6: AiVideoService.generate (每次新建外部任务)
Room DB (novel_video_jobs / segments / character_sheets)
```

**现有调度机制**：
- `NovelVideoService.pickNextJob`（`NovelVideoService.kt:258-266`）查 `getRunningJobs` + 内存过滤 `SCREENPLAY_PENDING_REVIEW`/`PAUSED`
- 单 `pipelineJob: Job?` 协程（`NovelVideoService.kt:106`），多 job 严格串行
- `cancelFlag: AtomicBoolean`（`NovelVideoService.kt:63`）+ `isCancelled` lambda 协作式取消
- `PARTIAL_WAKE_LOCK`（`NovelVideoService.kt:115-118`）防 Doze CPU 休眠
- FGS `onTimeout`（Android 15+，`NovelVideoService.kt:211-218`）保留中间态不写 FAILED
- `NovelVideoDao.updateJobFinalStatus*IfNotFinished`（`NovelVideoDao.kt:65-75`）SQL WHERE 守卫防终态覆写（TOCTOU 修复，D1-D6）

### 1.2 缺口清单（对照 ArcReel）

| ArcReel 能力 | 现状 | 缺口 / 风险 |
|---|---|---|
| **lease + 心跳**（`worker_lease` 表 + `TASK_WORKER_LEASE_TTL_SEC=10s` + 每 3s 续约） | **完全缺失**。`currentJobId` 是 `@Volatile var`，仅内存可见 | 进程死亡后无痕迹，无法判断「GENERATING 已 3 小时」是正常长任务还是孤儿 |
| **orphan 巡检**（`_handle_orphan_tasks_on_start` + lease lost 重扫） | **完全缺失**。仅靠 App 重启时 `pickNextJob` 重新拉取 | **不重启 App 永不恢复**；无 BootReceiver / WorkManager 周期巡检 |
| **provider_job_id 持久化**（`Task.provider_job_id` 列 + `ProviderJobIdPersistenceMixin`） | **`JobIdStore` 已存在但未被流水线调用**（`JobIdStore.kt` 空架子）。`NovelVideoSegment` 无 provider_job_id 字段 | Stage 5/6 中途崩溃盲目重提交，**重复扣费**（视频 API 通常更贵） |
| **resume_video 路径**（`execute_resume_video_task` + `MediaGenerator.resume_video_async` + `VideoBackend.resume_video`） | `VideoBackend.resumeVideo` 已实现（子项目 A），但**流水线层从未调用** | 长视频任务进程重启后从头重跑，而非接续轮询 |
| **NON_RESUMABLE_VIDEO_PROVIDERS 分流**（Grok/Vidu 同步型孤儿标 failed 而非重跑） | **完全缺失** | Grok/Vidu 孤儿盲目重提交，对已提交请求二次扣费 |
| **CapacityTable / SlotTable**（per-provider × per-media_type 并发池） | 单 `pipelineJob` 串行；segment 级 `async` 批并发（`NovelVideoParams.concurrency` 1-4） | 多 job 无法横向扩容；多 provider 无法独立限流 |
| **结构化失败码**（`task_failure.encode_failure` + i18n render） | 裸 `errorMessage` 字符串 | 失败原因不可机器解析，UI 无法按类型展示 |
| **任务去重唯一索引**（`idx_tasks_dedupe_active`） | 无 | 重复入队同一资源 |

### 1.3 会丢任务的场景

1. **进程崩溃 + 用户不重开 App** — job 卡在 `GENERATING`，无 lease 过期、无巡检，永远不被重新调度
2. **FGS 6 小时超时** — 保留中间态后 `stopSelf()`，若用户不及时重开 App，任务悬空
3. **`SCREENPLAY_PENDING_REVIEW` 状态** — 服务停了就不轮询，审阅页崩溃后 job 永远停在 PENDING_REVIEW

### 1.4 会重复扣费的场景

1. **Stage 5/6 中途崩溃** — segment 停在 `IMAGE_GENERATING`/`VIDEO_GENERATING`，恢复时重新进入对应 stage 重新提交（再扣一次 API 费用）
2. **`IMAGE_COMPLETED` 但本地文件被清理** — `imageUrl` 非空跳过生图，但文件不存在导致参考图缺失或重新生图
3. **`retryJob` 智能重置** — 保留 `imageUrl` 跳过生图是优化，但文件损坏时不报错
4. **双 ViewModel 并发操作** — `TaskCenterViewModel` 和 `JobDetailViewModel` 各自独立 `jobOpMutex`，retry 的 segment 重置（无条件 `updateSegmentStatus`）与 cancel 路径不互斥

## 2. 关键决策（待用户确认）

### 决策 1：移植范围

| 方案 | 内容 | 工程量 | 风险覆盖 |
|---|---|---|---|
| **A. 全量移植** | 持久化队列 + lease + orphan 恢复 + CapacityTable/SlotTable 多 worker + provider_job_id + resume 路径 + 结构化失败码 + 去重 | ~1500 行 Kotlin | 全部缺口 |
| **B. 最小补丁** | provider_job_id 持久化 + resume 路径 + orphan 巡检（WorkManager 周期扫描）+ NON_RESUMABLE 分流。保持单 worker 串行 | ~400-600 行 | 丢任务 + 重复扣费（核心风险） |
| **C. 中间方案** | B + lease/心跳（但单 worker，lease 仅用于孤儿判定）+ 结构化失败码。不做多 worker 并发池 | ~800-1000 行 | B + 孤儿精准判定 + 失败码可解析 |

### 决策 2：lease 机制是否保留

ArcReel 的 `worker_lease` 表设计用于**多进程互斥**（多个 worker 进程抢同一个 lease name）。Android 单进程前台服务模型下：

- **正方**：lease 可作为「worker 存活证明」——`leaseExpiresAt < now` 即判定 worker 已死，孤儿可被重调度。比纯时间启发式（「updatedAt 超 1 小时算孤儿」）更精准
- **反方**：单进程无「另一个 worker 抢 lease」场景，lease 退化为「心跳时间戳」，不如直接用 `workerHeartbeatAt` 列命名清晰

**建议**：决策 1 选 A 则保留 lease（语义完整）；选 B/C 则用 `workerHeartbeatAt` 列（语义更直白）

### 决策 3：多 worker 是否做

ArcReel 的 CapacityTable/SlotTable 支持 per-provider × per-media_type 并发池，多 worker 抢任务。

- **现状**：单 `pipelineJob` 串行，但 segment 级 `async` 批并发（1-4）
- **正方**：多 job 可并行（如 agnes 图片 job + ark 视频 job 同时跑），提升吞吐
- **反方**：手机资源有限（CPU/内存/网络），多 job 并行易 OOM；现有 segment 级并发已部分覆盖

**建议**：决策 1 选 A 则做（完整移植）；选 B/C 则不做（保持串行，segment 级并发够用）

### 决策 4：orphan 巡检触发方式

| 方式 | 优点 | 缺点 |
|---|---|---|
| **WorkManager 周期任务**（15 min） | 系统级，App 被杀也能拉起 | 15 min 最小间隔（Android 限制）；需保活 WorkManager |
| **BootReceiver** | 开机即恢复 | 仅开机触发一次，中途崩溃不恢复 |
| **App 启动钩子**（Application.onCreate / 首页 onResume） | 实现简单，无额外权限 | 用户不打开 App 永不恢复（同现状） |
| **前台服务自检**（服务启动时扫一次） | 同 ArcReel `_handle_orphan_tasks_on_start` | 仅服务启动时触发，需配合 WorkManager 兜底 |

**建议**：前台服务启动时扫一次（即时恢复）+ WorkManager 周期任务（兜底，15 min）

### 决策 5：Room schema 迁移

`NovelVideoJob` 需加列：`workerId?`、`workerHeartbeatAt?`（或 `leaseExpiresAt?`）、`providerJobId?`
`NovelVideoSegment` 需加列：`providerJobId?`、`providerId?`（用于 NON_RESUMABLE 分流）

当前 schema 版本 110 → 111，需写 `MIGRATION_110_111`（参考现有 `MIGRATION_109_110` 在 `DatabaseMigrations.kt:44-131`）

## 3. 方案 A：全量移植设计

### 3.1 数据模型变更

**`NovelVideoJob` 新增列**：

| 列 | 类型 | 用途 |
|---|---|---|
| `workerId` | String? | 认领该 job 的 worker 标识（单进程可用固定值 "default"） |
| `workerHeartbeatAt` | Long? | worker 最近心跳时间（epoch ms），null 表示未被认领 |
| `providerJobId` | String? | 提交后从 backend 返回的 provider 端任务 ID（resume 用） |
| `providerId` | String? | 入队时派生的 provider ID（NON_RESUMABLE 分流 + resume 锁定） |
| `attempts` | Int | 尝试次数（默认 0） |
| `availableAt` | Long | 下次可调度时间（默认 0，回队延迟用） |

**`NovelVideoSegment` 新增列**：

| 列 | 类型 | 用途 |
|---|---|---|
| `providerJobId` | String? | Stage 6 视频任务的 provider 端 ID（resume 用） |
| `providerId` | String? | Stage 6 使用的 video provider（NON_RESUMABLE 分流） |

### 3.2 调度层重构

```
UI (ViewModel)
  ↓ NovelVideoService.start/stop/cancelCurrentJob
NovelVideoService (前台服务)
  ↓ ensurePipelineRunning → N 个 worker 协程（CapacityTable 限并发）
  ↓ 每个 worker: claimJob(原子 UPDATE WHERE leaseExpired) → runOneJob
NovelVideoGenerator.generate(jobId, isCancelled)
  ↓ Stage 5: AiImageService.generate → 持久化 image provider_job_id
  ↓ Stage 6: AiVideoService.generate → 持久化 video provider_job_id
  ↓ 崩溃恢复: NovelVideoGenerator.resumeJob(jobId)
    ├─ Segment 状态 IMAGE_GENERATING + provider_job_id → 调 image backend.resume?
    └─ Segment 状态 VIDEO_GENERATING + provider_job_id → 调 video backend.resumeVideo
    └─ Segment 状态 VIDEO_GENERATING + provider ∈ NON_RESUMABLE → mark failed [resume_unsupported_provider]
Room DB (新增 worker/lease/provider_job_id 列)
```

**新增组件**：

1. **`GenerationQueue.kt`**（移植 `generation_queue.py`）— 队列 facade，`enqueue`/`claimNextJob`/`markSucceeded`/`markFailed`/`markCancelled`/`renewLease`
2. **`GenerationWorker.kt`**（移植 `generation_worker.py`）— worker 主循环 + CapacityTable + SlotTable + orphan handler
3. **`TaskFailure.kt`**（移植 `task_failure.py`）— 结构化失败码 encode/render
4. **`OrphanRecoveryWorker.kt`**（WorkManager 周期任务）— 15 min 扫一次孤儿
5. **`BootReceiver.kt`** — 开机触发 orphan 扫描

### 3.3 CapacityTable / SlotTable

- **CapacityTable**：per-provider × per-media_type 并发上限，从 `AiVideoProviderConfig.defaultParamsJson` 读 `video_max_workers`/`image_max_workers`，回退默认（video=1, image=2）
- **SlotTable**：内存台账，`Map<Pair<providerId, mediaType>, Set<jobId>>`，`hasRoom`/`register`/`release`

### 3.4 resume 路径

`NovelVideoGenerator.resumeJob(jobId)`：
1. 读 job + segments
2. 对每个 `VIDEO_GENERATING` segment：
   - `providerId ∈ NON_RESUMABLE`（grok/vidu）→ mark failed `[resume_unsupported_provider]`
   - `providerJobId == null` → mark failed `[restart_lost_no_job_id]`
   - 有 `providerJobId` → 调 `VideoBackend.resumeVideo(providerJobId, request)`，`ResumeExpiredError` → mark failed `[resume_expired_detail]`
3. 对每个 `IMAGE_GENERATING` segment → mark failed `[restart_lost_image]`（image 无 resume，重生成）
4. 继续未完成的 stage

## 4. 方案 B：最小补丁设计

### 4.1 数据模型变更

**`NovelVideoSegment` 新增列**（仅 segment 级，job 不动）：

| 列 | 类型 | 用途 |
|---|---|---|
| `providerJobId` | String? | Stage 6 视频任务的 provider 端 ID |
| `providerId` | String? | Stage 6 使用的 video provider |

### 4.2 核心改动

1. **`NovelVideoGenerator.processSegment`** Stage 6 提交后调 `JobIdStore.save(providerId, providerJobId, model, promptDigest)`，完成后 `clear`。失败时 `load` 检查是否有持久化 job_id
2. **`NovelVideoGenerator.resumeSegment`** 新增：对 `VIDEO_GENERATING + providerJobId != null` 的 segment 调 `VideoBackend.resumeVideo`；`NON_RESUMABLE` 分流标 failed
3. **`NovelVideoGenerator.generate`** 入口：先扫该 job 的 `VIDEO_GENERATING` segment，调 `resumeSegment`，再进正常 stage
4. **`OrphanRecoveryWorker.kt`**（WorkManager 15 min 周期）：扫 `status = GENERATING AND updatedAt < now - 1h` 的 job，标记 `orphan_detected` 触发服务重启
5. **`BootReceiver.kt`**：开机触发 `NovelVideoService.start`

### 4.3 不做的

- 不加 lease/心跳（用 `updatedAt` 时间启发式判孤儿）
- 不做多 worker（保持串行）
- 不加结构化失败码（保持裸 errorMessage）
- 不加任务去重唯一索引

## 5. 方案 C：中间方案设计

B 的全部 + lease/心跳（`workerHeartbeatAt` 列）+ 结构化失败码。不做多 worker。

### 5.1 数据模型变更

**`NovelVideoJob` 新增列**：

| 列 | 类型 | 用途 |
|---|---|---|
| `workerHeartbeatAt` | Long? | worker 最近心跳，null 表示未被认领 |

**`NovelVideoSegment` 新增列**：同方案 B

### 5.2 核心改动

B 的全部 + 
1. **`NovelVideoService.runOneJob`** 每 30s 更新 `workerHeartbeatAt`（单独协程或 stage 边界）
2. **`OrphanRecoveryWorker`** 判孤儿条件改为 `status = GENERATING AND (workerHeartbeatAt == null OR workerHeartbeatAt < now - 5min)`
3. **`TaskFailure.kt`** 结构化失败码（简化版，仅 resume 相关：`[resume_unsupported_provider]`/`[restart_lost_no_job_id]`/`[resume_expired_detail]`/`[restart_lost_image]`）

## 6. 可移植性评估（ArcReel → Android）

### 6.1 难以直接搬运的设计

| ArcReel 设计 | 依赖 | Android 评估 |
|---|---|---|
| `worker_lease` 表多进程互斥 | 多进程 + DB 共享 | **删除**。单进程无意义，用 `workerHeartbeatAt` 列替代 |
| `_ORPHAN_RESCAN_LEASE_LOST_MULT` lease flap 重扫 | 跨进程接管语义 | **删除**。单进程无 flap |
| `asyncio.shield` 包裹 DB 写入 | asyncio 取消传播 | Kotlin `withContext(NonCancellable)` 等价 |
| FastAPI HTTP API + SSE | HTTP server | 替换为前台服务 binder + Flow |
| `async_session_factory` + aiosqlite + SQLAlchemy | async DB driver | Room（已用） |
| `ConfigService` DB-backed provider 配置 | DB | SharedPreferences（已用 `AiVideoProviderConfig`） |

### 6.2 建议保留的设计

- **`WHERE status=...` 守卫 + 0-rows 协议**（现有 `IfNotFinished` 已实现，扩展到 lease 判定）
- **`CapacityTable` / `SlotTable` 分离**（方案 A）
- **`provider_job_id` 持久化 + resume 路径**（方案 B/C 核心）
- **`NON_RESUMABLE_VIDEO_PROVIDERS` 分流**（防重复扣费红线）
- **`AmbiguousSubmitError` 歧义态保护**（子项目 A 已实现，保留）
- **池满回队不 mark_failed**（方案 A）

## 7. 验收标准

### 通用（所有方案）

1. Stage 6 视频任务提交后 `providerJobId` 持久化到 DB
2. Stage 6 中途崩溃后恢复时，优先调 `resumeVideo` 而非重新提交
3. `providerId ∈ {grok, vidu}` 的 segment 崩溃后标 failed，不重提交
4. `ResumeExpiredError` 正确标 failed，不无限重试
5. CI unit-test 通过

### 方案 A 额外

6. 多 job 可并行（受 CapacityTable 限制）
7. lease 过期后 job 可被其他 worker 认领
8. 结构化失败码可被 UI 解析

### 方案 B/C 额外

6. WorkManager 周期任务 15 min 扫一次孤儿
7. BootReceiver 开机触发服务重启
8. （仅 C）`workerHeartbeatAt` 列每 30s 更新

## 8. 风险与回退

- 每方案独立提交，CI 红可 `git revert`
- Room migration v110→v111 需仔细测试（现有 `MIGRATION_109_110` 作参考）
- resume 路径需对每个 video backend 单独验证（子项目 A 已实现 `resumeVideo`，但流水线层首次调用）
- WorkManager 周期任务需声明 `RECEIVE_BOOT_COMPLETED` 权限

## 9. 已确认决策

1. **方案 A/B/C 选哪个**（决策 1）—— ✅ **方案 A 全量移植**
2. **lease 机制保留与否**（决策 2）—— ✅ 保留 lease（语义完整，单进程用固定 owner_id "default"）
3. **多 worker 做不做**（决策 3）—— ✅ 做（完整移植 CapacityTable/SlotTable）
4. **orphan 巡检触发方式**（决策 4）—— ✅ 前台服务启动时扫一次 + WorkManager 15 min 周期兜底 + BootReceiver 开机触发
5. **Room schema 迁移**（决策 5）—— ✅ v110→v111
