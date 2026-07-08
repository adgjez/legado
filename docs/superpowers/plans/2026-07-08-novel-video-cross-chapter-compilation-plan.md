# 跨章节拼接成整部视频 — 实现计划

- 日期：2026-07-08
- 子项目：E
- 关联 spec：`docs/superpowers/specs/2026-07-08-novel-video-cross-chapter-compilation-design.md`

## 阶段划分

### P0 数据模型与迁移
- 新增 `app/src/main/java/io/legado/app/data/entities/NovelVideoCompilation.kt`（按 spec §4.1）
- `DatabaseMigrations.kt` 新增 `migration_111_112`（建表 + 2 索引），加入 `migrations` 数组
- `AppDatabase.kt` `version = 112`，`entities` 列表追加 `NovelVideoCompilation::class`
- ~~编译生成 `schemas/io.legado.app.data.AppDatabase/112.json` 并提交~~
  - **实际处理**：schema 文件遵循项目惯例不纳入版本控制（git 跟踪止于 109.json，110/111/112 均未提交，CI 不校验 schema），本地由 KSP 编译自动生成
- 验收：编译通过，migration 在 Robolectric 集成测试中验证（`NovelVideoDaoRobolectricTest` / `GenerationQueueDaoTest` 的 in-memory DB 含 `NovelVideoCompilation` 实体）

### P1 DAO 接口
- `NovelVideoDao.kt` 追加 6 个 compilation 方法（spec §6）
- 验收：编译通过

### P2 VideoMuxer 可见性
- `VideoMuxer.checkFormatConsistency`：`private` → `public`（签名不变）
- 补回归测试 `VideoMuxerCheckFormatConsistencyTest`：public 入口可直接调用，返回 null/错误描述
- **实际处理**：核心比对逻辑抽为 `internal fun compareTrackKeys` 纯函数（行为不变），单测覆盖三分支（一致 / mime 不同 / 分辨率不同）+ mime 优先级 + public 入口 null 短路分支。Robolectric 下 ShadowMediaExtractor 不解析 mp4，无法用真实文件测完整路径，故拆分纯函数保证核心逻辑真覆盖
- 验收：现有 `merge` 行为不变（`merge` 内部仍调 `checkFormatConsistency`），新测试绿

### P3 NovelVideoCompiler（TDD）
- 先写 `NovelVideoCompilerTest`（spec §9.1 的 9 项）
- 实现 `NovelVideoCompiler.compile`（spec §5）：校验链 + 排序 + 一致性预检 + 复用 merge + 落库 + 失败清理
- 错误信息包装：原始「第 N 段」替换为 job 标识
- 验收：9 项单测全绿

### P4 ViewModel 扩展
- `NovelVideoTaskCenterViewModel`：`_compilations` Flow + `compileJobs` + `deleteCompilation`
- 验收：编译通过

### P5 UI
- `NovelVideoTaskCenterScreen`：已完成 Tab 长按多选 + ActionBar「拼成整部视频」按钮 + 进度/错误对话框
- 新增「整部视频」Tab：compilation 列表 + 打开/分享/删除
- 验收：编译通过，手动交互可达

### P6 推送 + CI
- 推送 main，CI 绿
- 验收：CI run success

## 验收标准（同 spec §11）
1. 已完成 Tab 可多选 ≥2 个同书 COMPLETED job 触发编译
2. 整部视频入口可见新条目，可打开/分享/删除
3. 格式不一致给出明确错误，不留半成品
4. 删除源 job 不影响已产出整部视频
5. Room v111→v112 迁移正常
6. 单测全绿，CI 通过
7. checkFormatConsistency 提为 public 后 merge 行为不变
