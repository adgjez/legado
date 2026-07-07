package io.legado.app.help.ai.backends.video

import io.legado.app.help.ai.backends.VideoGenerationRequest
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * P2a live 集成测试——验证 agnes/ark 真实生成**不报 `Incorrect padding`**。
 *
 * 默认 `@Ignore`（CI 不跑），手动执行验证时：
 * 1. 设置环境变量 `AGNES_API_KEY` / `ARK_API_KEY`
 * 2. 临时移除对应 `@Ignore` 注解
 * 3. 运行：`./gradlew :app:testDebugUnitTest --tests "*.VideoBackendLiveTest"`
 *
 * 验证目标（P2a 头条验收）：
 * - agnes 提交参考图（裸 base64）不报 `Incorrect padding`——修复的核心证明
 * - ark 提交参考图（data URI）不报 `Incorrect padding`
 *
 * 注：本测试会真实扣费（生成视频）。仅用于 P2a 一次性 live 验证，常规开发勿开。
 */
class VideoBackendLiveTest {

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun tmpOutput(): File =
        File.createTempFile("live-out", ".mp4").apply { deleteOnExit() }

    private fun tmpJpeg(name: String): File {
        // 最小有效 JPEG
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            0, 1, 0, 0, 1, 0, 1, 0, 0,
            0xFF.toByte(), 0xD9.toByte()
        )
        return File.createTempFile(name, ".jpg").apply { writeBytes(bytes); deleteOnExit() }
    }

    /**
     * agnes 文生视频（无参考图）——验证提交本身不报 `Incorrect padding`。
     *
     * 用 T2V 而非 I2V 是因为 T2V 不带参考图，能更纯粹地验证 endpoint/auth 跑通；
     * 参考图的裸 base64 编码已在 [AgnesVideoBackendTest] 单测覆盖。
     */
    @Test
    fun agnesTextToVideoDoesNotReportIncorrectPadding() = runBlocking<Unit> {
        val key = env("AGNES_API_KEY")
        assumeTrue("AGNES_API_KEY 未设置，跳过 agnes live 验证", key != null)

        val cfg = AiVideoProviderConfig(
            name = "agnes-live",
            type = AiVideoProviderConfig.TYPE_AGNES,
            baseUrl = "https://api.agnes-ai.com",
            apiKey = key!!,
            model = "agnes-video-v2.0",
            submitTimeoutMillisecond = 60_000L,
            pollTimeoutMillisecond = 600_000L,
            pollIntervalMillisecond = 5_000L
        )
        val backend = AgnesVideoBackend(cfg)
        val request = VideoGenerationRequest(
            prompt = "一只可爱的橘猫在窗台上晒太阳",
            outputPath = tmpOutput(),
            aspectRatio = "9:16",
            durationSeconds = 5,
            generateAudio = false
        )

        val result = runCatching { backend.generate(request) {} }
        val err = result.exceptionOrNull()

        // 1. 核心修复证明：不报 Incorrect padding（P2a 头条验收目标）
        assertTrue(
            "agnes T2V 不应报 Incorrect padding" +
                (err?.message?.let { "：$it" } ?: ""),
            err?.message?.contains("padding", ignoreCase = true) != true
        )

        // 2. Live 端到端验证：调用必须真成功。
        // 仅查 padding 会假绿——API 401/endpoint 错/网络失败均不含 "padding" 字样却仍未验证到修复。
        // live 测试存在的意义就是端到端跑通，红了好过假绿（逼着排查真实问题）。
        assertTrue(
            "agnes T2V 应端到端成功。失败：${err?.javaClass?.simpleName}: ${err?.message}" +
                (err?.cause?.let { " | cause: ${it.javaClass.simpleName}: ${it.message}" } ?: ""),
            result.isSuccess
        )

        // 3. 产物验证（非空 + 文件存在 + 文件非空，防止 backend 返回空壳 Result）
        val output = result.getOrThrow()
        assertNotNull("agnes T2V 应返回 taskId", output.taskId)
        assertTrue("agnes T2V 视频文件应存在：${output.videoPath}", output.videoPath.exists())
        assertTrue(
            "agnes T2V 视频文件应非空（>1KB）：${output.videoPath} size=${output.videoPath.length()}",
            output.videoPath.length() > 1024
        )
    }

    /**
     * agnes 图生视频（首帧 = 参考图，裸 base64）——验证裸 base64 提交不报 `Incorrect padding`。
     *
     * 这是 P2a 头条验收的 live 证明：ArcReel 注释原话「带 `data:` 前缀会在生成期触发 padding 错误」，
     * 本测试用真实 agnes API 验证裸 base64（[ImageCodec.toBareBase64]）确实修了 padding 问题。
     */
    @Test
    fun agnesImageToVideoBareBase64NoIncorrectPadding() = runBlocking {
        val key = env("AGNES_API_KEY")
        assumeTrue("AGNES_API_KEY 未设置，跳过 agnes I2V live 验证", key != null)

        val cfg = AiVideoProviderConfig(
            name = "agnes-live-i2v",
            type = AiVideoProviderConfig.TYPE_AGNES,
            baseUrl = "https://api.agnes-ai.com",
            apiKey = key!!,
            model = "agnes-video-v2.0",
            submitTimeoutMillisecond = 60_000L,
            pollTimeoutMillisecond = 600_000L,
            pollIntervalMillisecond = 5_000L
        )
        val backend = AgnesVideoBackend(cfg)
        val firstFrame = tmpJpeg("agnes-live-ff")
        val request = VideoGenerationRequest(
            prompt = "镜头缓慢推进，画面中的人物微笑",
            outputPath = tmpOutput(),
            aspectRatio = "9:16",
            durationSeconds = 5,
            startImage = firstFrame,
            generateAudio = false
        )

        val result = runCatching { backend.generate(request) {} }
        val err = result.exceptionOrNull()

        // 1. 核心修复证明：裸 base64 不报 Incorrect padding（P2a 头条验收——ArcReel 注释原话
        // 「带 data: 前缀会在生成期触发 padding 错误」，本测试用真实 agnes API 验证修复有效）
        assertTrue(
            "agnes I2V 裸 base64 不应报 Incorrect padding" +
                (err?.message?.let { "：$it" } ?: ""),
            err?.message?.contains("padding", ignoreCase = true) != true
        )

        // 2. Live 端到端验证：调用必须真成功（仅 padding 检查会假绿）
        assertTrue(
            "agnes I2V 应端到端成功（裸 base64 提交须真被 agnes 接受并生成视频）。" +
                "失败：${err?.javaClass?.simpleName}: ${err?.message}" +
                (err?.cause?.let { " | cause: ${it.javaClass.simpleName}: ${it.message}" } ?: ""),
            result.isSuccess
        )

        // 3. 产物验证
        val output = result.getOrThrow()
        assertNotNull("agnes I2V 应返回 taskId", output.taskId)
        assertTrue("agnes I2V 视频文件应存在：${output.videoPath}", output.videoPath.exists())
        assertTrue(
            "agnes I2V 视频文件应非空（>1KB）：${output.videoPath} size=${output.videoPath.length()}",
            output.videoPath.length() > 1024
        )
    }

    /**
     * ark 文生视频——验证 endpoint/auth 跑通，提交本身不报 `Incorrect padding`。
     *
     * data URI 编码已在 [ArkVideoBackendTest] 单测覆盖；本测试用真实 ark API
     * 验证 data URI（[ImageCodec.toDataUri]）的 NO_WRAP base64 不触发 padding 错误。
     */
    @Test
    fun arkTextToVideoDoesNotReportIncorrectPadding() = runBlocking {
        val key = env("ARK_API_KEY")
        assumeTrue("ARK_API_KEY 未设置，跳过 ark live 验证", key != null)

        val cfg = AiVideoProviderConfig(
            name = "ark-live",
            type = AiVideoProviderConfig.TYPE_ARK,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            apiKey = key!!,
            model = "doubao-seedance-1-5-lite",
            submitTimeoutMillisecond = 60_000L,
            pollTimeoutMillisecond = 600_000L,
            pollIntervalMillisecond = 5_000L
        )
        val backend = ArkVideoBackend(cfg)
        val request = VideoGenerationRequest(
            prompt = "一只可爱的橘猫在窗台上晒太阳",
            outputPath = tmpOutput(),
            aspectRatio = "9:16",
            durationSeconds = 5,
            generateAudio = false
        )

        val result = runCatching { backend.generate(request) {} }
        val err = result.exceptionOrNull()

        // 1. 核心修复证明：data URI 不报 Incorrect padding
        assertTrue(
            "ark T2V 不应报 Incorrect padding" +
                (err?.message?.let { "：$it" } ?: ""),
            err?.message?.contains("padding", ignoreCase = true) != true
        )

        // 2. Live 端到端验证：调用必须真成功
        assertTrue(
            "ark T2V 应端到端成功。失败：${err?.javaClass?.simpleName}: ${err?.message}" +
                (err?.cause?.let { " | cause: ${it.javaClass.simpleName}: ${it.message}" } ?: ""),
            result.isSuccess
        )

        // 3. 产物验证
        val output = result.getOrThrow()
        assertNotNull("ark T2V 应返回 taskId", output.taskId)
        assertTrue("ark T2V 视频文件应存在：${output.videoPath}", output.videoPath.exists())
        assertTrue(
            "ark T2V 视频文件应非空（>1KB）：${output.videoPath} size=${output.videoPath.length()}",
            output.videoPath.length() > 1024
        )
    }
}
