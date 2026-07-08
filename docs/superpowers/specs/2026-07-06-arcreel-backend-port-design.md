# ArcReel Backend 层移植设计（子项目 A）

- **日期**：2026-07-06
- **状态**：待审查
- **子项目**：A（Provider Backend 层）——「把 ArcReel 搬到 Legado」完整移植的第 1 个子项目
- **关联**：前置 spec `2026-07-05-novel-to-video-in-legado-design.md`；后续子项目 B（编排流水线）、C（UI 工作台）、D（数据持久化）另起 spec

## 1. 背景与动机

Legado 现有视频/图像生成 backend 存在结构性问题：

- `AiVideoService` / `AiImageService` 是单例 object + 巨型 when-block，按 `config.type` 字符串分发到私有函数（`submitAgnesJson`/`submitDoubaoJson`/`submitMultipart`、`generateByAgnesImagesApi`/`generateByOpenAi`/...），**没有 per-backend 类抽象**。
- 编码逻辑散落，靠 `isChatVisionModel`/`isAgnesImageModel` 启发式兜底（创可贴），导致 `Invalid image: Incorrect padding` 等编码 bug。
- ArcReel（`github.com/ArcReel/ArcReel`）是更成熟的开源「小说转视频」工作台，其 `lib/video_backends/*.py` + `lib/image_backends/*.py` 采用每家一个 backend 类、按 API 期望格式分别编码（Agnes 裸 base64、Seedance data URI、Sora 原始字节、Veo SDK Image、Kling 裸 base64）的纪律，独立压缩管线（`reference_compression.py`），双能力声明模型。

本子项目把 ArcReel 的 backend 层忠实全量移植到 Legado（Kotlin），替换现有 backend 代码，修编码 bug 并扩展 Provider 广度。

## 2. 范围

**包含**：
- 移植 ArcReel 全部 11 个 video backend（ark/agnes/sora/veo/kling/newapi/v2/dashscope/minimax/vidu/grok）
- 移植全部 9 个 image backend（agnes/ark/dashscope/gemini/grok/kling/minimax/openai/vidu）
- 移植双能力模型（`VideoCapability` set + `VideoCapabilities` dataclass）
- 移植压缩管线（`ReferenceCompressor` + `ImageCodec` + FRAME/ARRAY 角色 + 降档梯子 + 413 续档）
- 移植 `MediaGenerator` 咽喉层（压缩 + 413 + 调 backend）
- 移植共享 HTTP 工具（`submitPost`/`pollWithRetry`/`downloadVideo` + 重试谓词 + 429 + `AmbiguousSubmitError`）
- 服务层薄壳化（`AiVideoService` / `AiImageService`）
- 配置模型 type 常量换 ArcReel 名
- 删除老 backend 代码 + JS backend + 老 type 配置

**不包含**（属其他子项目）：
- 子项目 B：NovelVideoGenerator 编排流水线重写（本子项目仅做调用点兼容改动，不重写流水线）
- 子项目 C：UI 工作台
- 子项目 D：数据持久化（Project/Shot/Character 等领域模型）
- ArcReel `custom_provider/loader.py`（用户自定义 backend 逃生口）——用户已删 JS，无此需求

## 3. 关键决策（已与用户确认）

| 决策 | 选定 | 备选 |
|---|---|---|
| 移植形态 | 完整移植（含编排流水线 + UI），分 4 子项目 | 仅 backend / 旁路服务 / 嵌入式 |
| 首个子项目 | A：Provider Backend 层 | B/C/D |
| Backend 范围 | 忠实全量移植（11 video + 9 image） | 务实重构 / 最小修复 |
| 现有配置处理 | 删除（老 type 配置丢弃，用户重设） | 门面映射 / 并行共存 |
| JS backend | 一并删除 | 保留 |
| 抽象架构 | 方案 1：普通 interface + 每家一个类（动态分发经 VideoBackendRegistry） | abstract class / 函数式 registry / sealed interface |
| 生命周期模型 | 1a 忠实：backend.generate() 自管 submit+poll+download | 1b 适配：backend 只管请求+解析 |

## 4. 架构总览

### 4.1 生命周期模型（1a 忠实）

backend.generate() 自管 submit+poll+download 全生命周期。cancellation 通过 suspend 函数在 suspension point（`delay`/HTTP `await`）自然响应 `CoroutineScope.cancel`。进度通过 `onProgress: (VideoProgress) -> Unit` 回调（对应 ArcReel `poll_with_retry.on_progress`）。429 Retry-After、`AmbiguousSubmitError`、重试谓词在共享 `VideoBackendHttp` 内。413 续档在 `MediaGenerator` 咽喉层。`AiVideoTaskPoller` 整个类删除（被 backend.generate + MediaGenerator + VideoBackendHttp 全替代）。

### 4.2 文件布局

```
app/src/main/java/io/legado/app/help/ai/backends/
├── VideoBackend.kt              // sealed interface + 共享类型（Request/Result/Capabilities/Progress）
├── ImageBackend.kt              // sealed interface + 共享类型
├── VideoBackendRegistry.kt
├── ImageBackendRegistry.kt
├── VideoBackendHttp.kt          // submitPost/pollWithRetry/downloadVideo + 重试谓词 + 429 + AmbiguousSubmitError
├── MediaGenerator.kt            // runWithReferenceCompression（压缩 + 413 续档 + 调 backend）
├── JobIdStore.kt                // resume_video 用的 jobId 持久化
├── video/
│   ├── ArkVideoBackend.kt       // Seedance 1.5/2.0
│   ├── AgnesVideoBackend.kt
│   ├── SoraVideoBackend.kt      // ArcReel openai.py
│   ├── VeoVideoBackend.kt       // ArcReel gemini.py
│   ├── KlingVideoBackend.kt
│   ├── NewApiVideoBackend.kt
│   ├── V2VideoBackend.kt        // v2_video_generations
│   ├── DashScopeVideoBackend.kt
│   ├── MiniMaxVideoBackend.kt
│   ├── ViduVideoBackend.kt
│   └── GrokVideoBackend.kt
├── image/
│   ├── AgnesImageBackend.kt
│   ├── ArkImageBackend.kt       // doubao-seedream
│   ├── DashScopeImageBackend.kt // qwen-image
│   ├── GeminiImageBackend.kt
│   ├── GrokImageBackend.kt
│   ├── KlingImageBackend.kt
│   ├── MiniMaxImageBackend.kt
│   ├── OpenAiImageBackend.kt    // gpt-image
│   └── ViduImageBackend.kt
└── compress/
    ├── ReferenceCompressor.kt   // 移植 reference_compression.py
    ├── ImageCodec.kt            // 移植 image_utils.py（EXIF 矫正 + 缩放重编码 + 编码工具）
    └── RefRole.kt               // ARRAY / FRAME 枚举
```

## 5. 接口契约

### 5.1 VideoBackend

```kotlin
interface VideoBackend {
    val typeId: String                          // "ark"/"agnes"/...
    val model: String
    val capabilities: Set<VideoCapability>      // 端点级
    val videoCapabilities: VideoCapabilities    // 参考图级
    suspend fun generate(
        request: VideoGenerationRequest,
        onProgress: (VideoProgress) -> Unit = {}
    ): VideoGenerationResult
    suspend fun resumeVideo(jobId: String, request: VideoGenerationRequest): VideoGenerationResult
}
```

> 实现注：用普通 `interface` 而非 `sealed interface`。各 backend 实现在 `backends/video/` 子包，Kotlin sealed 跨包不允许；动态分发经 `VideoBackendRegistry`（按 typeId 查表），无需 exhaustive when，故 sealed 无收益。

### 5.2 双能力模型（移植 `video_backends/base.py:378-405`）

```kotlin
enum class VideoCapability {
    TEXT_TO_VIDEO, IMAGE_TO_VIDEO, GENERATE_AUDIO,
    NEGATIVE_PROMPT, VIDEO_EXTEND, SEED_CONTROL, FLEX_TIER
}

data class VideoCapabilities(
    val firstFrame: Boolean = true,
    val lastFrame: Boolean = false,
    val referenceImages: Boolean = false,
    val maxReferenceImages: Int = 0,
    val referenceImagesWithStartFrame: Boolean = false
)
```

每个 backend 提供伴生纯函数 `fun videoCapabilitiesForModel(model: String): VideoCapabilities`（不需 client 即可计算），instance 的 `videoCapabilities` 委托它——统一 ArcReel 的不对称（6 静态方法 + 5 property）为单一真相源。各家能力值：

| backend | 能力值 |
|---|---|
| ark | seedance-2: last_frame=true, reference_images=true, max=9；否则默认（first_frame=true） |
| agnes | all true, max=4, with_start_frame=false |
| sora | reference_images=true, max=1, with_start_frame=false（首帧与参考共享槽位） |
| veo | last_frame=true, reference_images=true, max=3, with_start_frame=false |
| kling | 按 model 查表 5 档（v2-5-turbo/v3/v3-omni/v2-6/video-o1） |
| newapi | 仅 first_frame=true |
| v2 | all true, max=4, with_start_frame=true |
| dashscope | 按 model 查表 6 档（happyhorse/wan2.7 × t2v/i2v/r2v） |
| minimax | Hailuo-2.3: first_frame=true；S2V-01: first_frame=false, refs=true, max=1 |
| vidu | first_frame=true, last_frame=true, refs=true, max=7, with_start_frame=false |
| grok | refs=true, max=7, with_start_frame=true |

### 5.3 Request/Result（移植 `base.py:408-448`）

```kotlin
data class VideoGenerationRequest(
    val prompt: String,
    val outputPath: File,
    val aspectRatio: String = "9:16",
    val durationSeconds: Int = 5,
    val resolution: String? = null,
    val startImage: File? = null,
    val endImage: File? = null,
    val referenceImages: List<File>? = null,
    val generateAudio: Boolean = true,
    val projectName: String? = null,
    val taskId: String? = null,
    val serviceTier: String = "default",
    val seed: Long? = null
) {
    // 实现为扩展函数：internal fun VideoGenerationRequest.withCompressedRefs(refs)
    // 放在 AiVideoService.kt，便于 MediaGenerator 调用时与 buildSpecs 串联
}

data class VideoGenerationResult(
    val videoPath: File, val provider: String, val model: String,
    val durationSeconds: Int, val videoUri: String? = null,
    val seed: Long? = null, val usageTokens: Long? = null,
    val taskId: String? = null, val generateAudio: Boolean? = null
)

data class VideoProgress(val status: String, val message: String? = null)
```

### 5.4 ImageBackend

```kotlin
sealed interface ImageBackend {
    val typeId: String
    val model: String
    val capabilities: Set<ImageCapability>
    suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult
}
enum class ImageCapability { TEXT_TO_IMAGE, IMAGE_TO_IMAGE }
data class ReferenceImage(val path: String, val label: String = "")
data class ImageGenerationRequest(
    val prompt: String, val outputPath: File,
    val referenceImages: List<ReferenceImage> = emptyList(),
    val aspectRatio: String = "9:16",
    val imageSize: String? = null,
    val projectName: String? = null,
    val seed: Long? = null
)
data class ImageGenerationResult(
    val imagePath: File, val provider: String, val model: String, val usageTokens: Long? = null
)
```

### 5.5 Registry（backend 按配置构造，非单例）

backend 持有 baseUrl/apiKey/model/paramsJson 状态，故每次按配置实例化。

```kotlin
object VideoBackendRegistry {
    private val factories: Map<String, (AiVideoProviderConfig) -> VideoBackend> = mapOf(
        "ark" to ::ArkVideoBackend, "agnes" to ::AgnesVideoBackend, "sora" to ::SoraVideoBackend,
        "veo" to ::VeoVideoBackend, "kling" to ::KlingVideoBackend, "newapi" to ::NewApiVideoBackend,
        "v2" to ::V2VideoBackend, "dashscope" to ::DashScopeVideoBackend,
        "minimax" to ::MiniMaxVideoBackend, "vidu" to ::ViduVideoBackend, "grok" to ::GrokVideoBackend
    )
    fun byConfig(cfg: AiVideoProviderConfig): VideoBackend =
        (factories[cfg.type] ?: error("未知视频 backend type: ${cfg.type}"))(cfg)
}
// ImageBackendRegistry 同构（9 家）
```

## 6. 共享 HTTP 工具（移植 `video_backends/base.py:208-362`）

```kotlin
object VideoBackendHttp {
    suspend fun submitPost(postFn: suspend () -> okhttp3.Response, provider: String): okhttp3.Response
    // 非幂等 POST：传输歧义→AmbiguousSubmitError（终态不重试，避免重复扣费）
    // 429→VideoRateLimitedException(retryAfterSeconds)，内部按 Retry-After 等待重试上限 5 次，不计入 submit 重试
    suspend fun <T> pollWithRetry(
        pollFn: suspend () -> T,
        isDone: (T) -> Boolean, isFailed: (T) -> Boolean,
        pollInterval: Duration, maxWait: Duration,
        retryIf: ((Throwable) -> Boolean)? = null,
        label: String = "",
        onProgress: (VideoProgress) -> Unit = {}
    ): T
    suspend fun downloadVideo(url: String, outputPath: File, timeout: Duration = 120.seconds)
    fun shouldRetrySubmit(e: Throwable): Boolean   // 4xx(非429) false，5xx/网络 true
    fun shouldRetryPoll(e: Throwable): Boolean      // 404 true
    fun shouldRetryDownload(e: Throwable): Boolean  // 404 false
}
class AmbiguousSubmitError(message: String) : IllegalStateException(message)
class VideoRateLimitedException(val retryAfterSeconds: Long?, message: String) : IllegalStateException(message)
```

## 7. 逐家编码映射

### 7.1 Video backends（11）

| typeId | 默认 model | 端点 | 请求体 | 参考图编码 | 响应解析 |
|---|---|---|---|---|---|
| ark | doubao-seedance-2-0 | Ark POST /contents/generations/tasks | content[]{type:text/image_url, image_url:{url}, role} | data URI | $.id→poll $.status→$.content.video_url |
| agnes | agnes-video-v2.0 | Agnes POST /v1/videos/generations | {model,prompt,width,height,num_frames,frame_rate, image?, extra_body:{image:[],mode?}} | **裸 base64** | $.id→poll→$.video_url |
| sora | sora-2 | OpenAI POST /v1/videos | multipart input_reference | **原始字节** | $.id→poll→url |
| veo | veo-3.1 | Gemini SDK generate_videos | reference_images:[Image] | **SDK Image 对象** | SDK result |
| kling | kling-v2-5-turbo | Kling POST /v1/videos/generations | {model_name,prompt, image?, image_tail?, image_list:[{image}]} | **裸 base64** | $.task_id→poll→task_result.videos[0].url |
| newapi | (用户填) | NewAPI POST /videos/generations | {model,prompt, image?} | data URI | $.id→poll→url |
| v2 | (用户填) | V2 POST /v2/video_generations | {image_url?, last_image_url?, image_urls:[]} | data URI | $.id→poll→url |
| dashscope | happyhorse-1.0-i2v | DashScope POST /services/aigc/multimodal-generation/generation | input.messages[0].content[]{image}/{text} | data URI | $.output.task_id→poll→url |
| minimax | video-01 | MiniMax POST /video_generation | {model,prompt, first_frame_image?, subject_reference?:[{type:character,image_file}]} | data URI | $.task_id→poll→file_id→url |
| vidu | viduq2 | Vidu POST /ent/v2/{text2video/img2video/reference2video} | {model,prompt, images?:[], aspect_ratio, resolution} | data URI | $.id→poll→$.creations[0].url |
| grok | grok-3-video | xAI SDK | {model,prompt, image?, image_urls?:[]} | data URI | $.id→poll→url |

### 7.2 Image backends（9）

| typeId | 默认 model | 端点 | 请求体 | 参考图编码 | 响应解析 |
|---|---|---|---|---|---|
| agnes | agnes-image-2.1-flash | POST /images/generations | {model,prompt,n,size, image?:[]} | data URI 列表 | data[0].url 降级 b64_json |
| ark | doubao-seedream-5-0-lite | Ark SDK images.generate | {model,prompt,size, image?:str|[]} | data URI | data[0].b64_json 降级 url |
| dashscope | qwen-image-2.0 | POST /services/aigc/multimodal-generation/generation | input.messages[0].content[]{image}/{text} | data URI | extract_image_url 下载 |
| gemini | gemini-3.1-flash-image-preview | Gemini SDK generate_content | contents:[label,Image,label,Image,prompt] | **Bitmap 对象**（非 base64） | parts[i].inline_data→save |
| grok | grok-imagine-image | xAI SDK image.sample | {prompt,model,aspect_ratio, image_urls?:[]} | data URI 列表 | response.url 下载 |
| kling | kling-image-o1 | POST /v1/images/generations 异步 | {model_name,prompt,aspect_ratio,n, image?:[]} | **裸 base64 列表** | poll→task_result.images[0].url |
| minimax | image-01 | POST /image_generation | {model,prompt,width,height,response_format,n, subject_reference?:[{type:character,image_file}]} | data URI（单脸 ref） | extract_image_url 降级 b64_json |
| openai | gpt-image-2 | OpenAI SDK images.generate(T2I)/images.edit(I2I) | T2I:{model,prompt,n,size}; I2I:{model,image:[BinaryIO],prompt} | **原始字节**（文件句柄列表） | data[0].b64_json 降级 url + usage token |
| vidu | viduq2 | POST /reference2image 异步 | {model,prompt,images?:[], aspect_ratio,resolution} | data URI 列表 | poll→creations[0].url |

### 7.3 共享编码工具（`ImageCodec.kt`，移植 `image_backends/base.py` + `image_utils.py`）

```kotlin
object ImageCodec {
    fun toDataUri(file: File): String {       // ark/newapi/v2/dashscope/minimax/vidu/grok/agnes(image)/openai(T2I) 用
        val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)  // NO_WRAP 防 padding 错
        return "data:${mimeByExtension(file.extension)};base64,$b64"
    }
    fun toBareBase64(file: File): String =    // agnes(video)/kling(video+image) 用
        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    fun toRawBytes(file: File): ByteArray = file.readBytes()  // sora/openai(I2I) 用
    suspend fun compress(bytes: ByteArray, maxLongEdge: Int, quality: Int, subsampling: Int = 0): ByteArray
    fun mimeByExtension(ext: String): String
}
```

`Base64.NO_WRAP` 是关键——ArcReel 注释原话「带 `data:` 前缀会在生成期触发 padding 错误」，Android 默认 `Base64.DEFAULT` 每 76 字符插换行也触发同样错误。

### 7.4 Gemini/Veo 的 Bitmap 路径

ArcReel 用 PIL.Image；Android 用 `android.graphics.Bitmap`。gemini(image)/veo(video) 从文件 `BitmapFactory.decodeFile` 加载。Gemini Android SDK 可用性待验证；无 SDK 时走 REST API + raw bytes multipart 兜底。spec 标注为「SDK 可用性待验证，REST 兜底」。

## 8. 压缩管线（移植 `reference_compression.py`）

### 8.1 核心模型

```kotlin
enum class RefRole { ARRAY, FRAME }   // ARRAY=多参考图走梯子; FRAME=首/尾帧永不缩尺寸
data class ReferenceSpec(val source: File, val label: String, val role: RefRole)
data class CompressedRef(val path: File, val label: String, val role: RefRole)
data class PayloadLimits(
    val totalMaxBytes: Long = 8 * 1024 * 1024,
    val singleMaxBytes: Long = 4 * 1024 * 1024
)
class ReferencePayloadFloorError(cause: Throwable) : IllegalStateException("参考图压到地板仍超上限", cause)
```

### 8.2 降档梯子（照搬 `reference_compression.py:34-41`）

```kotlin
object ReferenceCompressor {
    private val LADDER = listOf(2048 to 92, 1536 to 90, 1280 to 90, 1024 to 88)
    private val FLOOR = 1024 to 80
    private const val FRAME_NO_RESIZE_EDGE = 1_000_000   // FRAME 永不缩尺寸哨兵
    private const val PASSTHROUGH_MAX_BYTES = 1 * 1024 * 1024
    private const val SUBSAMPLING_444 = 0
    val LADDER_STEPS = LADDER.size  // 4

    suspend fun selectLadderStep(raws, roles, limits, startStep=0): Pair<Int, List<ByteArray>>
    suspend fun compressSingleAtStep(raw, role, step, singleMaxBytes): ByteArray
    suspend inline fun <T> withCompressedPayload(specs, limits, startStep=0, block: (landedStep, List<CompressedRef>) -> T): T
}
```

**关键不变量**：
- FRAME 永不缩尺寸（仅超 single 才 q92 重编码，连 PNG 都不强转）
- ARRAY step0 透传：已是 JPEG + <1MB + 尺寸合规 → 原字节透传避免二压
- 不可解码/非本地源 → 原路径透传，**绝不 raise**（压缩是优化，不得让原本能跑通的调用因压缩层新失败）
- 透传项不计字节预算，但按原序位合并回完整列表保 1:1
- 临时文件沿用源 stem（gemini 按文件名推断参考图名）

### 8.3 EXIF 矫正（`ImageCodec.compress` 内）

`ExifInterface` 读 Orientation tag 旋转，替代 PIL `ImageOps.exif_transpose`。仅进入压缩的图（ARRAY 重编码 + FRAME 超字节重编码）走矫正；透传字节不矫正。Android 大图解码用 `BitmapFactory.Options.inSampleSize` 采样避免 OOM（ArcReel PIL 在服务端内存充裕，Android 需此适配）。

## 9. MediaGenerator 咽喉层（移植 `media_generator._run_with_reference_compression`）

压缩 + 413 被动续档 + 调 backend。视频和图像都走它。

```kotlin
object MediaGenerator {
    suspend fun <T> runWithReferenceCompression(
        specs: List<ReferenceSpec>,
        limits: PayloadLimits,
        buildAndCall: suspend (List<CompressedRef>) -> T
    ): T {
        if (specs.isEmpty()) return buildAndCall(emptyList())  // T2I/T2V 无参考图直接单次，413 不降档
        var step = 0
        while (true) {
            ReferenceCompressor.withCompressedPayload(specs, limits, startStep = step) { landed, compressed ->
                try { return buildAndCall(compressed) }
                catch (e: Throwable) {
                    if (!is413(e)) throw e
                    if (landed < ReferenceCompressor.LADDER_STEPS) { step = landed + 1 }
                    else throw ReferencePayloadFloorError(e)
                }
            }
        }
    }
    private fun is413(e: Throwable): Boolean
}
```

`withContext(Dispatchers.Default)` 包压缩（CPU 密集），`withContext(Dispatchers.IO)` 包磁盘/HTTP——对应 ArcReel `asyncio.to_thread`。

## 10. 服务层薄壳化

```kotlin
object AiVideoService {
    suspend fun generate(
        request: VideoGenerationRequest,
        provider: AiVideoProviderConfig? = null,
        onProgress: (VideoProgress) -> Unit = {}
    ): VideoGenerationResult = withContext(Dispatchers.IO) {
        val target = resolveProvider(provider)
        val backend = VideoBackendRegistry.byConfig(target)
        val limits = resolvePayloadLimits(target)
        val specs = buildSpecs(request, backend)   // FRAME/ARRAY 分配 + 尾帧三级 fallback
        MediaGenerator.runWithReferenceCompression(specs, limits) { compressed ->
            backend.generate(request.withCompressedRefs(compressed), onProgress)
        }
    }
}
object AiImageService {
    suspend fun generate(request: ImageGenerationRequest, provider: AiImageProviderConfig? = null): ImageGenerationResult =
        withContext(Dispatchers.IO) {
            val target = resolveProvider(provider)
            val backend = ImageBackendRegistry.byConfig(target)
            val limits = resolvePayloadLimits(target)
            val specs = request.referenceImages.map { ReferenceSpec(File(it.path), it.label, RefRole.ARRAY) }
            MediaGenerator.runWithReferenceCompression(specs, limits) { compressed ->
                backend.generate(request.copy(referenceImages = compressed.map { ReferenceImage(it.path, it.label) }))
            }
        }
    // generateAndStore 保留（调 generate 后存 AiImageGalleryManager）
}
```

`buildSpecs` 的尾帧三级 fallback（移植 `media_generator.py:534-742`）：尾帧时若 backend 不支持 last_frame 但支持 reference_images → 尾帧降级为参考图；都不支持 → 忽略并 warning。首/尾帧 → `ReferenceSpec(role=FRAME)`；参考图数组 → `ReferenceSpec(role=ARRAY)`。

## 11. 删除清单

| 文件/符号 | 处置 |
|---|---|
| `AiVideoTaskPoller` 整个类 | 删除（backend.generate + MediaGenerator + VideoBackendHttp 全替代） |
| `AiVideoService`: submitMultipart/submitAgnesJson/submitDoubaoJson/buildMultipartBody/mergeProviderParams/buildSubmitUrl/inferOriginFromPollUrl/parseSizeStatic/computeAgnesNumFramesStatic/parseDoubaoResolution/VideoSubmitRequest/resolveJsonPath/submit(旧)/poll/downloadToLocal | 删除（逻辑迁入对应 backend 类） |
| `AiVideoService.VideoRateLimitedException` | 删除（并入 `VideoBackendHttp.submitPost`） |
| `AiImageService`: generateByOpenAi/generateByAgnesImagesApi/generateByImagesApi/generateByResponses/generateByChatWithImages/generateByJs/isChatVisionModel/isAgnesImageModel + 3 个 when 块 | 删除 |
| `AiConfigModels`: TYPE_OPENAI/TYPE_JS/TYPE_DOUBAO（video）+ TYPE_OPENAI/TYPE_JS/TYPE_AGNES（image 旧） | 删除，换 ArcReel type 常量 |
| `AppConfig.normalizeAiVideoProviders/normalizeAiImageProviders` | 重写：老 type 丢弃，新 type 白名单 |
| `AiVideoProviderEditActivity` veo multipart 预置 + doubao 预置 | 换 ArcReel 11 家预置 |
| `AiImageProviderManageActivity` openai/js/agnes 选择器 | 换 ArcReel 9 家选择器 |
| `strings.xml` ai_image_provider_agnes 等 | 换新 type 字符串 |

## 12. 配置模型变更

```kotlin
data class AiVideoProviderConfig(... type: String = TYPE_ARK ...) {
    companion object {
        const val TYPE_ARK="ark"; const val TYPE_AGNES="agnes"; const val TYPE_SORA="sora"
        const val TYPE_VEO="veo"; const val TYPE_KLING="kling"; const val TYPE_NEWAPI="newapi"
        const val TYPE_V2="v2"; const val TYPE_DASHSCOPE="dashscope"; const val TYPE_MINIMAX="minimax"
        const val TYPE_VIDU="vidu"; const val TYPE_GROK="grok"
    }
}
// AiImageProviderConfig: agnes/ark/dashscope/gemini/grok/kling/minimax/openai/vidu
```

`defaultParamsJson` 保留，语义变为 per-provider 高级参数 + per-provider 压缩上限覆盖（`reference_total_max_bytes`/`reference_single_max_bytes`）。老配置（type=openai/js/doubao）启动时丢弃并日志提示用户重设。

## 13. 错误处理

| 场景 | 处理 |
|---|---|
| HTTP 429 | `VideoBackendHttp.submitPost` 抛 `VideoRateLimitedException(retryAfterSeconds)`，内部按 Retry-After 等待重试上限 5 次，不计入 submit 重试 |
| 非幂等提交传输歧义 | `AmbiguousSubmitError`（终态不重试，避免重复扣费） |
| HTTP 413 | `MediaGenerator` 捕获 → `landed+1` 降档续档，上限 LADDER_STEPS(4)，耗尽抛 `ReferencePayloadFloorError` 保 cause |
| 4xx（非 429/413） | `shouldRetrySubmit` false，终态失败 |
| 5xx/网络瞬态 | `shouldRetrySubmit`/`shouldRetryPoll` true，指数退避重试 |
| 响应解析缺 taskId/videoUrl | 仅提示 JSON path 缺失，**不回显响应体**（保 N7 安全——响应体可能含 Provider 内部堆栈/密钥片段） |
| Cancellation | suspend 函数在 `delay`/HTTP await 处自然响应，`onProgress` 不再触发 |

## 14. NovelVideoGenerator 兼容（不重写，仅改调用点）

NovelVideoGenerator 是子项目 B 的重写对象，但子项目 A 不能让它编译断裂。最小改动：把对 `AiVideoTaskPoller`（已删）的调用改成 `AiVideoService.generate(request, provider, onProgress)`。流水线 Stage 1-6 结构不动（B 再重写）。`AiImageGalleryManager.saveGeneratedImage` 适配新 `ImageGenerationResult.imagePath`。

## 15. 测试策略

**核心：每家 backend 一个单元测试，断言编码纪律**（`Incorrect padding` 修复的证明）。mock okhttp 拦截出站请求，断言参考图编码形态。

测试矩阵：

| 层 | 测试 | 数量级 |
|---|---|---|
| 20 个 backend | 编码形态 + 响应解析 + 能力值（ark seedance-2 分支、kling 5 档查表等） | ~60-80 用例 |
| `ReferenceCompressor` | 梯子降档 / FRAME 不缩尺寸 / ARRAY step0 透传 / 地板抛错 / 不可解码透传不 raise / EXIF 矫正 | ~15 用例 |
| `MediaGenerator` | 413 续档从 landed+1 / 地板耗尽抛错保 cause / 无参考图 413 不降档 | ~6 用例 |
| `VideoBackendHttp` | 429 Retry-After / AmbiguousSubmitError / shouldRetry 谓词 | ~8 用例 |
| `Registry` | byConfig 分发 / 未知 type 报错 | ~4 用例 |
| 迁移 | 老 type 配置 normalize 丢弃 | ~3 用例 |

集成测试（真 HTTP）仅用户有 key 的 backend（agnes、ark/doubao）跑，其余 `@Ignore("无 API key")`。GSON 替代 org.json（Android 单测 stub 问题）。

## 16. 迁移与回退

- **迁移**：首次启动老 type（openai/js/doubao）配置丢弃 + 日志提示「视频/图像 Provider 配置已重置，请按 ArcReel 类型重新添加」。无数据迁移（用户「删除」意图）。
- **jobId 持久化**：移植 `ProviderJobIdPersistenceMixin` 为本地 `JobIdStore`（resume_video 用），最小实现。
- **回退**：git revert。单用户无灰度。

## 17. 已知风险与适配

| 风险 | 适配 |
|---|---|
| Gemini/Veo Android SDK 可用性 | 无等价 SDK 时走 REST + raw bytes multipart 兜底，标注「待验证」 |
| Android Bitmap OOM | `BitmapFactory.Options.inSampleSize` 采样兜底（ArcReel PIL 服务端内存充裕，Android 需此适配） |
| 20 backend 大部分无法 live 验证 | 无 key 的仅靠 mock 单测保证编码形态；用户拿到 key 后端到端验证 |
| 老 veo3.1 multipart 用户配置丢失 | 老 type=openai 丢弃，用户以 type=veo（Gemini）重配，迁移提示说明 |
| ArcReel `custom_provider/loader.py` 不移植 | 用户自定义 backend 逃生口不移植（已删 JS，无需求），未来需要另开子项 |
| compression CPU 阻塞 | `withContext(Dispatchers.Default)` 包压缩，`Dispatchers.IO` 包磁盘/HTTP |

## 18. 开放问题

无。所有关键决策已在第 3 节与用户确认。

## 19. 验收标准

1. 20 个 backend 类全部实现，每个有编码形态单测通过。
2. `ReferenceCompressor` + `MediaGenerator` 单测全通过（含 413 续档、地板抛错、透传不 raise）。
3. `VideoBackendHttp` 单测全通过（429 Retry-After、AmbiguousSubmitError）。
4. 老 type 配置 normalize 丢弃，新 type 白名单生效。
5. `NovelVideoGenerator` 调用点适配后编译通过，不重写流水线。
6. `AiVideoTaskPoller` 删除，无残留引用。
7. CI unit-test job 通过。
8. 用户有 key 的 backend（agnes、ark）集成测试可跑通（不再报 `Incorrect padding`）。
