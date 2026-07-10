# Provider 配置 UI 重构（按 ArcReel 模型）

- **日期**：2026-07-10
- **状态**：待审查
- **范围**：视频供应商 + 图像供应商的编辑/新建 UI 重构（不含列表页、任务级下拉、数据模型）

## 1. 背景与动机

ArcReel backend 层已全量移植到 legado（11 video + 9 image backend），但配置 UI 还是 legado 老式长表单，体验差：

### 当前问题（以 [AiVideoProviderEditScreen.kt](file:///workspace/app/src/main/java/io/legado/app/ui/config/AiVideoProviderEditScreen.kt) 为例）

1. **类型选择是干巴巴一行**：顶部一个 clickable card 显示"类型: ark"，点开是个纯文字列表（[AiVideoProviderEditActivity.kt#L172-L192](file:///workspace/app/src/main/java/io/legado/app/ui/config/AiVideoProviderEditActivity.kt#L172-L192)），没有说明、没有引导。
2. **长表单平铺十几个技术字段**：submitUrl/pollUrl/taskIdJsonPath/videoUrlJsonPath/statusJsonPath/doneStatusValue/failedStatusValue/maxReferenceImages/3 个 timeout… 全部一次性铺开，吓退普通用户。
3. **字段不区分类型**：所有类型看到同一套字段，但 backend 代码里每家内置了 `DEFAULT_MODEL` + `SUBMIT_PATH`/`POLL_PATH`/`ENDPOINT`（如 [ArkVideoBackend.kt#L306-L308](file:///workspace/app/src/main/java/io/legado/app/help/ai/backends/video/ArkVideoBackend.kt#L306-L308)），`cfg.submitUrl.ifBlank { baseUrl + SUBMIT_PATH }` / `cfg.model.ifBlank { DEFAULT_MODEL }` 早就支持留空用默认——UI 却没体现这点，没有 placeholder 提示默认值。
4. **残留 JS backend 分支**：`isOpenAi` / script / jsLib 分支（[AiVideoProviderEditScreen.kt#L269-L286](file:///workspace/app/src/main/java/io/legado/app/ui/config/AiVideoProviderEditScreen.kt#L269-L286)）在 ArcReel 移植时已删 JS backend，这些分支是死代码。
5. **baseUrl 写死空值 bug**：[AiVideoProviderEditActivity.kt#L233](file:///workspace/app/src/main/java/io/legado/app/ui/config/AiVideoProviderEditActivity.kt#L233) save() 里 `baseUrl = ""` 硬编码，用户填的 baseUrl 根本没保存——视频供应商的 baseUrl 字段在 UI 里压根没出现，是隐式损坏。
6. **无连通性验证**：填错 key/baseUrl 要等实际生成时才报错，排查成本高。

### ArcReel 的模型（参考）

ArcReel [CredentialList.tsx](https://github.com/ArcReel/ArcReel/blob/main/frontend/src/components/pages/CredentialList.tsx) 的核心：
- Provider 是**预设类型**（backend 内置 endpoint/path/默认 model），用户只添加 **Credential**（name + API key + 可选 base_url）。
- 一行凭证：名称 + masked key + 激活指示点 + 测试/编辑/删除图标按钮。
- 测试按钮：发轻量请求验证，返回成功/失败 + 可用模型列表。
- inline 编辑，不跳页。

### 决策（已与用户确认）

| 决策 | 选定 |
|---|---|
| 范围 | 视频 + 图像供应商编辑 UI 都改 |
| 多凭证 | **不要**——一家供应商只存一组配置。数据模型 `AiVideoProviderConfig` / `AiImageProviderConfig` 不动 |
| 连通性测试 | **要**——编辑页加测试按钮，单凭证下也有价值 |
| 整体方案 | 方案 A：类型卡片选择 + 动态表单 + 高级折叠 |

## 2. 设计

### 2.1 编辑页新结构（视频/图像共用）

进入编辑页（新建或编辑现有），自上而下：

```
┌─ TopAppBar（返回 + 标题"视频/图像供应商"） ─┐
│                                                │
│  ① 类型选择卡片区                              │
│     - 网格/列表展示所有预设类型                │
│     - 每张卡：类型名 + 一行简短说明            │
│     - 当前选中高亮                             │
│     - 点击切换类型                             │
│                                                │
│  ② 基础区（始终展示，跟随类型）                │
│     - 名称（必填）                             │
│     - API Key（必填，password）                │
│     - Base URL（选填，placeholder=默认域名）   │
│     - 模型（选填，placeholder=默认模型名）     │
│                                                │
│  ③ 高级区（默认折叠，可展开覆盖默认）          │
│     - 视频特有：Submit URL / Poll URL 模板     │
│       / TaskId JSONPath / VideoUrl JSONPath    │
│       / Status JSONPath / Done Status          │
│       / Failed Status / Max Reference Images   │
│     - 通用：自定义 Headers / Submit Timeout    │
│       / Poll Timeout / Poll Interval           │
│     - 每个字段 placeholder="留空用默认"        │
│     - 视频额外：默认参数 JSON（code editor）   │
│                                                │
│  ④ 启用开关                                    │
│                                                │
│  ⑤ 操作栏                                      │
│     [测试]              [保存]                 │
└────────────────────────────────────────────────┘
```

### 2.2 类型元数据（核心新增）

为每个类型定义一份元数据，驱动 UI 渲染。新建 `ProviderTypeMeta` 数据结构：

```kotlin
// 放在 ui/main/ai/ 或 ui/config/ 下新建 ProviderTypeRegistry.kt
data class ProviderTypeMeta(
    val typeId: String,
    val displayNameRes: Int,        // R.string.ai_video_provider_ark 等
    val descriptionRes: Int,        // 新增：一行简短说明，如"火山方舟豆包视频"
    val defaultBaseUrl: String,     // 官方默认域名，作 baseUrl placeholder
    val defaultModel: String,       // 内置默认模型，作 model placeholder
    val docUrl: String? = null      // 可选：该类型 API 文档链接，测试失败时提示
)

object VideoProviderTypeRegistry {
    val metas: List<ProviderTypeMeta> = listOf(
        meta(AiVideoProviderConfig.TYPE_ARK, R.string.ai_video_provider_ark,
             R.string.provider_desc_ark_video, "https://ark.cn-beijing.volces.com",
             "doubao-seedance-1-5-pro-251215"),
        meta(AiVideoProviderConfig.TYPE_AGNES, ...),
        // ... 11 家
    )
    fun get(typeId: String): ProviderTypeMeta = metas.firstOrNull { it.typeId == typeId } ?: error("未知 video provider type: $typeId")
}

object ImageProviderTypeRegistry {
    // 同理 9 家
}
```

**defaultModel 来源**：从各 backend `companion object` 的 `DEFAULT_MODEL` 常量提取。当前是 private，需提为 internal const，供 Registry 读取，避免两处维护漂移。这是唯一需要轻微碰 backend 层的地方。

**defaultBaseUrl 来源**：backend 代码里没有 DEFAULT_BASE_URL 常量（baseUrl 是从 config 传入的），需在 Registry 里**硬编码各类型官方默认域名**（从各 backend 代码的 URL 拼接逻辑反推，如 ark 用 `https://ark.cn-beijing.volces.com`）。Registry 旁加注释标注来源 backend 文件，便于后续校对。

### 2.3 类型选择卡片组件

新建 `ProviderTypeSelector` Composable（复用于视频/图像）：

```kotlin
@Composable
fun ProviderTypeSelector(
    metas: List<ProviderTypeMeta>,
    selectedTypeId: String,
    onSelect: (String) -> Unit
) {
    // FlowRow（编辑页是 verticalScroll，必须用 FlowRow 避免嵌套 Lazy 滚动冲突）
    // 每张卡：Card + Column { Text(displayName) + Text(description, secondaryText, maxLines=1) }
    // 选中态：border = accent / containerColor = accentDim
}
```

### 2.4 高级折叠区组件

```kotlin
@Composable
fun AdvancedConfigSection(
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    // Card header: "高级配置" + 展开图标（展开/收起箭头）
    // AnimatedVisibility 展开内容
}
```

字段复用现有 `EditTextField`，但 placeholder 统一标注默认值来源。视频特有字段（submitUrl 等）只在视频编辑页渲染；图像编辑页高级区只放 headers / timeout（图像无 poll）。

### 2.5 连通性测试

新建 `ProviderConnectionTester` object（suspend）：

```kotlin
object ProviderConnectionTester {
    data class Result(val success: Boolean, val message: String, val availableModels: List<String> = emptyList())

    suspend fun testVideo(config: AiVideoProviderConfig): Result
    suspend fun testImage(config: AiImageProviderConfig): Result
}
```

**测试策略**（轻量、不实际生成）：
- **视频**：发 submit 请求但带一个明显会被快速拒绝的参数（如 duration=0 或空 prompt），预期返回 4xx/业务错误码——能拿到响应就说明 key+baseUrl+网络通；或若该 backend 有"查询余额/模型列表"类轻量端点则优先用。
- **图像**：同理，或调 `/models` 列表端点（OpenAI 兼容的有）。
- **不实际生成**：避免消耗用户额度。
- 返回成功条件：HTTP 有响应（非网络错误/非 401/403）。401/403 → 失败+"API Key 无效"。网络异常 → 失败+"无法连接，检查 Base URL"。
- **不支持轻量测试的退化策略**：若某 backend 既无轻量端点又无法用"被拒参数"安全触发（如必须真实生成才能验证），则该类型测试按钮置灰 + 提示"该类型暂不支持连通性测试，请直接保存后试用"。测试方法在 Registry 元数据里用 `testStrategy: TestStrategy` 枚举标记（`LIGHTWEIGHT` / `REJECTED_REQUEST` / `UNSUPPORTED`），由 Tester 按 strategy 分派。

UI：测试按钮点击后显示 CircularProgressIndicator，结果以行内提示卡呈现（绿色成功/红色失败+原因），参考 ArcReel CredentialRow 的 testResult 展示。

### 2.6 baseUrl bug 修复

重构 save() 时，`baseUrl` 从 UI 输入取值（新增 baseUrl 字段到基础区），不再写死 `""`。这同时修掉 [AiVideoProviderEditActivity.kt#L233](file:///workspace/app/src/main/java/io/legado/app/ui/config/AiVideoProviderEditActivity.kt#L233) 的 bug。

### 2.7 死代码清理

重构中删除：
- `isOpenAi` 参数及所有 `if (isOpenAi)` 分支（ArcReel 移植已删 JS backend，全是死代码）
- `script` / `jsLib` 相关状态、UI、code editor 入口（video edit）
- 图像 edit 同理清理 JS 残留（若有）

`AiVideoProviderConfig.script` / `jsLib` 字段保留在 data class（向后兼容已存数据），但 UI 不再暴露入口。

## 3. 文件改动清单

### 新增
- `app/src/main/java/io/legado/app/ui/config/ProviderTypeMeta.kt` — 元数据 data class + Video/Image Registry
- `app/src/main/java/io/legado/app/ui/config/ProviderTypeSelector.kt` — 类型选择卡片 Composable
- `app/src/main/java/io/legado/app/ui/config/AdvancedConfigSection.kt` — 高级折叠区 Composable
- `app/src/main/java/io/legado/app/help/ai/backends/ProviderConnectionTester.kt` — 连通性测试

### 修改
- `AiVideoProviderEditScreen.kt` — 重写：类型卡片 + 基础区 + 高级折叠 + 测试按钮
- `AiVideoProviderEditActivity.kt` — 重写状态绑定/save()：修 baseUrl bug、删 script/jsLib、加测试触发
- `AiImageProviderEditScreen.kt` — 同理重写（图像无 poll 字段，高级区更简）
- `AiImageProviderEditActivity.kt` — 同理
- 各 backend `companion object` — `DEFAULT_MODEL` 从 private 提为 internal（供 Registry 读）
- `strings.xml` — 新增各类型 description 字符串 + "高级配置"/"测试"/"测试中"/"测试成功"/"测试失败"等

### 不动
- `AiVideoProviderConfig` / `AiImageProviderConfig` data class（字段不动，向后兼容）
- 列表页（`AiVideoProviderManageScreen` / `AiImageProviderManageScreen`）
- 任务级下拉（`NovelVideoJobConfigSheet` 的 ProviderDropdown）
- backend 运行时逻辑

## 4. 数据流

```
用户进编辑页
  → Activity 从 AppConfig 读 provider（或 null 新建）
  → 初始化 Compose state（name/apiKey/baseUrl/model/type/高级字段）
  → Screen 渲染：
      类型选择 → 用户点卡片切换 type → state.type 更新 → 基础区 placeholder 跟着变（registry.get(type)）
      基础区填写 → state 更新
      高级区展开（可选）→ 覆盖默认
      点测试 → Activity 调 ProviderConnectionTester → 显示结果
      点保存 → Activity 组装 config（baseUrl 从输入取，非 ""）→ AppConfig 持久化 → finish
```

## 5. 测试

- **连通性测试**：`ProviderConnectionTester` 写单元测试，mock HTTP 响应验证成功/401/网络异常分支。
- **类型 Registry**：断言 Video 11 家 / Image 9 家全覆盖，每家有非空 defaultBaseUrl/defaultModel。
- **UI**：手动验证（本地无 SDK，靠 CI 编译 + 设备走查）：
  - 新建视频供应商：选 ark → 基础区 placeholder 显示 ark 默认 → 留空 baseUrl/model 保存 → 后端用默认值
  - 编辑现有：字段正确回填
  - baseUrl 不再丢失：填了 baseUrl 保存后重进仍在
  - 高级区展开覆盖：填 submitUrl 保存 → backend 用填的值
  - 测试按钮：填对 key 显示成功，填错显示失败原因
- **回归**：现有 provider 配置（用户已存的）打开编辑页不崩溃，字段不丢。

## 6. 不包含

- 列表页 UI 改造（Manage Screen）——本次不动，保持现状
- 多凭证 / 激活切换——已决策不要
- 任务级 provider 下拉改造——保持现状
- backend 运行时逻辑改动——只提 DEFAULT_MODEL 可见性，不改逻辑
- ArcReel 的 Vertex AI JSON 凭证文件上传——不适用（legado 无此 backend）
