# ArcReel Backend 层移植实现计划（子项目 A）

- **日期**：2026-07-06
- **对应 spec**：`docs/superpowers/specs/2026-07-06-arcreel-backend-port-design.md`
- **执行方式**：分 8 阶段，每阶段末提交 + 推 CI，CI 绿才进下一阶段（review checkpoint）
- **总规模**：~25 新文件 + ~10 文件改动 + ~95 单测用例

## 阶段总览

| 阶段 | 内容 | 可验证产物 | 依赖 |
|---|---|---|---|
| P0 | 基础设施：共享类型 + ImageCodec + VideoBackendHttp + Registry 骨架 + 配置常量 | 编译过 + ImageCodec/VideoBackendHttp 单测 | — |
| P1 | 压缩管线：ReferenceCompressor + MediaGenerator + RefRole | 压缩/413 单测 | P0 |
| P2 | 11 video backend（分 4 批） | 每家编码形态单测 | P0,P1 |
| P3 | 9 image backend（分 3 批） | 每家编码形态单测 | P0,P1 |
| P4 | 服务薄壳化：AiVideoService.generate + AiImageService.generate + buildSpecs | 服务层单测 | P2,P3 |
| P5 | 删除老代码 + 配置迁移 + 预置 + strings | 老代码无残留 + normalize 单测 | P4 |
| P6 | NovelVideoGenerator 调用点兼容 | 编译过 + 现有流水线不破 | P4,P5 |
| P7 | CI 全绿 + agnes/ark 集成测试 | 不再报 Incorrect padding | P6 |

---

## P0 · 基础设施

**目标**：搭好共享层骨架，不实现任何具体 backend，但 ImageCodec 和 VideoBackendHttp 可独立单测。

**任务**：
1. 新建包 `app/src/main/java/io/legado/app/help/ai/backends/`
2. `VideoBackend.kt`：sealed interface + `VideoCapability` enum + `VideoCapabilities` dataclass + `VideoGenerationRequest/Result/Progress` + `ReferenceImage`（暂放，P1 可能挪到 compress）
3. `ImageBackend.kt`：sealed interface + `ImageCapability` enum + `ImageGenerationRequest/Result` + `ReferenceImage`
4. `compress/RefRole.kt`：`enum class RefRole { ARRAY, FRAME }`
5. `compress/ImageCodec.kt`：`toDataUri`/`toBareBase64`/`toRawBytes`/`mimeByExtension`/`compress`（含 EXIF + inSampleSize 采样兜底）
6. `VideoBackendHttp.kt`：`submitPost`/`pollWithRetry`/`downloadVideo`/`shouldRetrySubmit`/`shouldRetryPoll`/`shouldRetryDownload` + `AmbiguousSubmitError` + `VideoRateLimitedException`（429 Retry-After 上限 5）
7. `VideoBackendRegistry.kt` + `ImageBackendRegistry.kt`：factories map（先空，P2/P3 填）+ byConfig（未知 type 报错）
8. `JobIdStore.kt`：最小 jobId 持久化（resume_video 用）
9. `AiConfigModels.kt`：加 ArcReel type 常量（video 11 + image 9），**暂不删**老常量（P5 删，避免 P0-P4 编译断裂）

**验证**：
- `./gradlew :app:compileDebugKotlin` 通过
- `ImageCodecTest`：toDataUri 带 `data:` 前缀 + NO_WRAP 无换行；toBareBase64 无 `data:` 前缀；compress 缩尺寸 + EXIF 旋转
- `VideoBackendHttpTest`：429 Retry-After 重试上限 5；AmbiguousSubmitError 终态；shouldRetry 谓词
- `RegistryTest`：byConfig 未知 type 报错（factories 空时所有 type 报错）

**提交**：`feat(ai/backends): P0 基础设施（共享类型+ImageCodec+VideoBackendHttp+Registry 骨架）`

---

## P1 · 压缩管线

**目标**：ReferenceCompressor + MediaGenerator 咽喉层，独立单测。

**任务**：
1. `compress/ReferenceCompressor.kt`：
   - `LADDER`/`FLOOR`/`FRAME_NO_RESIZE_EDGE`/`PASSTHROUGH_MAX_BYTES`/`SUBSAMPLING_444` 常量
   - `selectLadderStep(raws, roles, limits, startStep)`：逐档下压到 total≤totalMax 且 ARRAY≤singleMax，地板抛 `ReferencePayloadFloorError`
   - `compressSingleAtStep(raw, role, step, singleMaxBytes)`：FRAME 超字节才 q92 重编码不缩尺寸；ARRAY step0 合规小 JPEG 透传
   - `withCompressedPayload(specs, limits, startStep, block)`：inline 函数，临时文件沿用源 stem，finally 清理；不可解码/非本地源透传原路径不 raise
   - `ReferenceSpec`/`CompressedRef`/`PayloadLimits`/`ReferencePayloadFloorError` 数据类
2. `MediaGenerator.kt`：
   - `runWithReferenceCompression(specs, limits, buildAndCall)`：无 specs 直接单次；413 从 landed+1 续档上限 LADDER_STEPS；耗尽抛 `ReferencePayloadFloorError` 保 cause
   - `is413(e)`：status_code/response.status_code/code==413 或 "payload too large"
   - `withContext(Dispatchers.Default)` 包压缩，`Dispatchers.IO` 包磁盘
3. 把 `ReferenceImage`/`ReferenceSpec`/`CompressedRef` 归位到 `compress/` 包

**验证**：
- `ReferenceCompressorTest`：梯子降档单调；FRAME 不缩尺寸；ARRAY step0 透传；地板抛错；不可解码透传不 raise；EXIF 矫正
- `MediaGeneratorTest`：413 续档从 landed+1（非请求 step）；地板耗尽抛错保 cause；无参考图 413 不降档直接抛

**提交**：`feat(ai/backends): P1 压缩管线（ReferenceCompressor+MediaGenerator 咽喉层）`

---

## P2 · 11 video backend（分 4 批）

每家一个文件 `video/<Name>VideoBackend.kt`，实现 sealed interface + 伴生 `videoCapabilitiesForModel` + `generate()`（submit+poll+download 全生命周期，用 VideoBackendHttp）。每家配编码形态单测（mock okhttp 断言请求体参考图编码）。每批末提交 + 推 CI。

### P2a · ark + agnes（用户有 key，可 live 验证）
1. `ArkVideoBackend.kt`：Seedance 1.5/2.0，content[] + data URI + role；`_is_seedance_2` 模型判定
2. `AgnesVideoBackend.kt`：**裸 base64**（剥 data 前缀，关键修 Incorrect padding）；extra_body.image 数组
3. Registry 注册 ark/agnes
4. `ArkVideoBackendTest`/`AgnesVideoBackendTest`：编码形态 + 能力值（ark seedance-2 分支 / agnes all true max=4）
5. **live 集成测试**：agnes/ark 真生成（@Ignore 默认，手动跑验证不报 Incorrect padding）

**提交**：`feat(ai/backends): P2a ark+agnes video backend（含裸 base64 修 padding）`

### P2b · sora + veo（原始字节/SDK 路径）
1. `SoraVideoBackend.kt`：multipart input_reference 原始字节
2. `VeoVideoBackend.kt`：Gemini SDK Image 对象；**REST 兜底**（无 Android SDK 时走 raw bytes multipart）
3. Registry 注册 sora/veo
4. 单测：sora 原始字节 multipart；veo SDK 可用性标注 + REST 兜底路径

**提交**：`feat(ai/backends): P2b sora+veo video backend（原始字节/SDK 路径）`

### P2c · kling + newapi + v2
1. `KlingVideoBackend.kt`：**裸 base64**；按 model 查 `_KLING_VIDEO_CAPS` 5 档表
2. `NewApiVideoBackend.kt`：data URI
3. `V2VideoBackend.kt`：data URI；with_start_frame=true
4. Registry 注册
5. 单测：kling 5 档能力查表 + 裸 base64；newapi/v2 data URI

**提交**：`feat(ai/backends): P2c kling+newapi+v2 video backend`

### P2d · dashscope + minimax + vidu + grok
1. `DashScopeVideoBackend.kt`：content {image}/{text} data URI；6 档能力查表
2. `MiniMaxVideoBackend.kt`：subject_reference data URI；2 档能力
3. `ViduVideoBackend.kt`：images data URI；async poll
4. `GrokVideoBackend.kt`：image/image_urls data URI
5. Registry 注册（video 11 家全齐）
6. 单测：各家编码形态 + 能力查表

**提交**：`feat(ai/backends): P2d dashscope+minimax+vidu+grok video backend（11 家全齐）`

---

## P3 · 9 image backend（分 3 批）

每家 `image/<Name>ImageBackend.kt`，实现 sealed interface + `generate()`。每家编码形态单测。

### P3a · agnes + ark（用户有 key）
1. `AgnesImageBackend.kt`：data URI 列表；data[0].url 降级 b64_json
2. `ArkImageBackend.kt`：data URI 单 str/多 list；b64_json 降级 url
3. Registry 注册
4. 单测 + live 集成测试

**提交**：`feat(ai/backends): P3a agnes+ark image backend`

### P3b · openai + gemini
1. `OpenAiImageBackend.kt`：T2I data URI / I2I **原始字节文件句柄列表**；b64_json 降级 url + usage token
2. `GeminiImageBackend.kt`：Bitmap 对象（非 base64）；parts[i].inline_data；REST 兜底
3. Registry 注册
4. 单测

**提交**：`feat(ai/backends): P3b openai+gemini image backend`

### P3c · dashscope + grok + kling + minimax + vidu
1. `DashScopeImageBackend.kt`：content {image}/{text} data URI
2. `GrokImageBackend.kt`：image_urls data URI 列表
3. `KlingImageBackend.kt`：**裸 base64 列表**；async poll
4. `MiniMaxImageBackend.kt`：subject_reference data URI 单脸 ref
5. `ViduImageBackend.kt`：images data URI 列表；async poll
6. Registry 注册（image 9 家全齐）
7. 单测

**提交**：`feat(ai/backends): P3c dashscope+grok+kling+minimax+vidu image backend（9 家全齐）`

---

## P4 · 服务薄壳化

**目标**：AiVideoService/AiImageService 缩成薄壳调 Registry + MediaGenerator + backend.generate。**此时老代码仍在（共存），保证不破现有调用**。

**任务**：
1. `AiVideoService.generate(request, provider, onProgress)`：resolveProvider → Registry.byConfig → resolvePayloadLimits → buildSpecs（FRAME/ARRAY 分配 + 尾帧三级 fallback）→ MediaGenerator.runWithReferenceCompression { backend.generate }
2. `AiImageService.generate(request, provider)`：同构（所有参考图 ARRAY 角色）
3. `AiImageService.generateAndStore`：调 generate 后存 AiImageGalleryManager
4. `buildSpecs` 辅助：尾帧 last_frame 不支持但 reference_images 支持 → 降级；都不支持 → warning 忽略
5. `resolvePayloadLimits`：从 defaultParamsJson 读 `reference_total_max_bytes`/`reference_single_max_bytes` 覆盖默认

**验证**：
- `AiVideoServiceTest`：generate 路径分发到 mock backend；buildSpecs 尾帧 fallback 三分支
- `AiImageServiceTest`：generate 路径分发；所有参考图 ARRAY
- 老调用点（NovelVideoGenerator/AiVideoTaskPoller）仍用老 API，**不破**

**提交**：`feat(ai/backends): P4 服务薄壳化（generate 新入口，老 API 共存）`

---

## P5 · 删除老代码 + 配置迁移

**目标**：删老 backend 代码 + AiVideoTaskPoller + JS + 老 type 常量，重写 normalize，换预置 + strings。**此时 NovelVideoGenerator 会编译断裂，P6 修**。

**任务**：
1. 删 `AiVideoService`：submitMultipart/submitAgnesJson/submitDoubaoJson/buildMultipartBody/mergeProviderParams/buildSubmitUrl/inferOriginFromPollUrl/parseSizeStatic/computeAgnesNumFramesStatic/parseDoubaoResolution/VideoSubmitRequest/resolveJsonPath/submit(旧)/poll/downloadToLocal/VideoRateLimitedException
2. 删 `AiImageService`：generateByOpenAi/generateByAgnesImagesApi/generateByImagesApi/generateByResponses/generateByChatWithImages/generateByJs/isChatVisionModel/isAgnesImageModel + 3 when 块
3. 删 `AiVideoTaskPoller.kt` 整个文件
4. `AiConfigModels.kt`：删 TYPE_OPENAI/TYPE_JS/TYPE_DOUBAO（video）+ TYPE_OPENAI/TYPE_JS/TYPE_AGNES（image 旧）
5. `AppConfig.normalizeAiVideoProviders/normalizeAiImageProviders`：老 type 丢弃 + 日志提示，新 type 白名单
6. `AiVideoProviderEditActivity`：删 veo multipart + doubao 预置，加 ArcReel 11 家预置
7. `AiImageProviderManageActivity`：删 openai/js/agnes 选择器，加 ArcReel 9 家
8. `strings.xml`：换新 type 字符串

**验证**：
- `AppConfigMigrationTest`：老 type 配置 normalize 丢弃；新 type 保留
- grep 确认无 `AiVideoTaskPoller`/`submitMultipart`/`TYPE_JS` 残留引用
- 编译**预期断裂**（NovelVideoGenerator 引用已删的 AiVideoTaskPoller）—— P6 修

**提交**：`refactor(ai/backends): P5 删除老 backend 代码+JS+AiVideoTaskPoller+老 type 常量`

---

## P6 · NovelVideoGenerator 调用点兼容

**目标**：把 NovelVideoGenerator 对 AiVideoTaskPoller 的调用改成 AiVideoService.generate，恢复编译。**不重写流水线**（子项目 B 干）。

**任务**：
1. 定位 NovelVideoGenerator 中所有 `AiVideoTaskPoller` 调用点
2. 改成 `AiVideoService.generate(VideoGenerationRequest(...), provider, onProgress)`，把原 submit/poll/download 的进度回调接 to onProgress
3. `AiImageGalleryManager.saveGeneratedImage` 适配新 `ImageGenerationResult.imagePath`
4. 其他对老 `AiVideoService.submit/poll/downloadToLocal` 的引用全部改 generate

**验证**：
- `./gradlew :app:compileDebugKotlin` 通过
- `./gradlew :app:assembleDebug` 通过
- 现有 NovelVideoGenerator 流水线逻辑未改（仅调用点改）

**提交**：`refactor(ai): P6 NovelVideoGenerator 调用点适配新 AiVideoService.generate`

---

## P7 · CI 全绿 + 集成验证

**目标**：CI unit-test job 通过，agnes/ark live 集成测试不再报 Incorrect padding。

**任务**：
1. 推送所有提交到 remote 触发 CI
2. CI 失败则修（预期：单测 stub 问题用 GSON、import 缺失等）
3. CI 绿后，手动跑 agnes/ark live 集成测试（取消 @Ignore）验证：
   - agnes video 裸 base64 参考图不再报 `Incorrect padding`
   - ark video data URI 参考图正常
   - agnes/ark image 生成正常
4. 重新加 @Ignore 保留集成测试代码

**验证**：
- CI run `unit-test` job conclusion=success
- agnes/ark live 不报 `Incorrect padding`

**提交**：`test(ai/backends): P7 CI 修复+agnes/ark 集成验证`

---

## 风险与回退

- 每阶段独立提交，任一阶段 CI 红可 `git revert <commit>` 回退到上一绿阶段
- P5 删除是大手术，若 P6 兼容改动过大可回退 P5 重新评估
- Gemini/Veo SDK 路径若阻塞 P2b，可先跳过 veo 实现（Registry 报错引导），P2b 仅做 sora，veo 留待 SDK 验证后补

## 验收（对应 spec 第 19 节）

1. 20 backend 类全实现 + 编码形态单测通过 ✓（P2/P3）
2. ReferenceCompressor + MediaGenerator 单测通过 ✓（P1）
3. VideoBackendHttp 单测通过 ✓（P0）
4. 老 type normalize 丢弃 ✓（P5）
5. NovelVideoGenerator 编译通过不重写 ✓（P6）
6. AiVideoTaskPoller 删除无残留 ✓（P5）
7. CI unit-test 通过 ✓（P7）
8. agnes/ark 集成不报 Incorrect padding ✓（P7）
