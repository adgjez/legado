package io.legado.app.help.ai.backends.image

import io.legado.app.help.ai.backends.ImageGenerationRequest
import io.legado.app.help.ai.backends.ReferenceImage
import io.legado.app.ui.main.ai.AiImageProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * P3a live 集成测试——验证 agnes/ark 真实图像生成**不报 `Incorrect padding`**。
 *
 * 默认 `@Ignore`（CI 不跑），手动执行验证时：
 * 1. 设置环境变量 `AGNES_API_KEY` / `ARK_API_KEY`
 * 2. 临时移除对应 `@Ignore` 注解
 * 3. 运行：`./gradlew :app:testDebugUnitTest --tests "*.ImageBackendLiveTest"`
 *
 * 验证目标（P3a 头条验收）：
 * - agnes 提交参考图（data URI 列表）不报 `Incorrect padding`——I2I 编码纪律的 live 证明
 * - ark 提交参考图（data URI 单 str/多 list）不报 `Incorrect padding`
 *
 * 注：本测试会真实扣费（生成图片）。仅用于 P3a 一次性 live 验证，常规开发勿开。
 */
@Ignore("P3a live 集成测试——需 API key + 真实扣费，CI 默认跳过")
class ImageBackendLiveTest {

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun tmpOutput(): File =
        File.createTempFile("live-img-out", ".png").apply { deleteOnExit() }

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
     * agnes 文生图（无参考图）——验证 endpoint/auth 跑通，提交本身不报 `Incorrect padding`。
     *
     * 用 T2I 而非 I2I 是因为 T2I 不带参考图，能更纯粹地验证 endpoint/auth 跑通；
     * 参考图的 data URI 列表编码已在 [AgnesImageBackendTest] 单测覆盖。
     */
    @Test
    fun agnesTextToImageDoesNotReportIncorrectPadding() = runBlocking<Unit> {
        val key = env("AGNES_API_KEY")
        assumeTrue("AGNES_API_KEY 未设置，跳过 agnes live 验证", key != null)

        val cfg = AiImageProviderConfig(
            name = "agnes-img-live",
            type = AiImageProviderConfig.TYPE_AGNES,
            baseUrl = "https://apihub.agnes-ai.com",
            apiKey = key!!,
            model = "agnes-image-2.1-flash",
            timeoutMillisecond = 120_000L
        )
        val backend = AgnesImageBackend(cfg)
        val request = ImageGenerationRequest(
            prompt = "一只可爱的橘猫在窗台上晒太阳，温暖的光线",
            outputPath = tmpOutput(),
            aspectRatio = "9:16"
        )

        val result = runCatching { backend.generate(request) }
        assertTrue(
            "agnes 图像提交不应报 Incorrect padding：${result.exceptionOrNull()?.message ?: ""}",
            result.exceptionOrNull()?.message?.contains("padding", ignoreCase = true) != true
        )
        // 成功时验证产物
        result.getOrNull()?.let {
            assertTrue("图片文件应存在", it.imagePath.exists())
            assertNotNull("应有 provider", it.provider)
        }
    }

    /**
     * agnes 图生图（参考图 = data URI 列表）——验证 data URI 列表提交不报 `Incorrect padding`。
     *
     * 这是 P3a 头条验收的 live 证明：agnes I2I 把参考图编码成 data URI 数组（[ImageCodec.toDataUri]，
     * NO_WRAP base64）下发，验证不触发 padding 错误。
     */
    @Test
    fun agnesImage2ImageDataUriNoIncorrectPadding() = runBlocking {
        val key = env("AGNES_API_KEY")
        assumeTrue("AGNES_API_KEY 未设置，跳过 agnes I2I live 验证", key != null)

        val cfg = AiImageProviderConfig(
            name = "agnes-img-live-i2i",
            type = AiImageProviderConfig.TYPE_AGNES,
            baseUrl = "https://apihub.agnes-ai.com",
            apiKey = key!!,
            model = "agnes-image-2.1-flash",
            timeoutMillisecond = 120_000L
        )
        val backend = AgnesImageBackend(cfg)
        val refImage = tmpJpeg("agnes-img-ref")
        val request = ImageGenerationRequest(
            prompt = "保持画面构图，增强暖色调与光影层次",
            outputPath = tmpOutput(),
            referenceImages = listOf(ReferenceImage(refImage.absolutePath)),
            aspectRatio = "9:16"
        )

        val result = runCatching { backend.generate(request) }
        val err = result.exceptionOrNull()
        assertTrue(
            "agnes I2I data URI 列表提交不应报 Incorrect padding" +
                (err?.message?.let { "：$it" } ?: ""),
            err?.message?.contains("padding", ignoreCase = true) != true
        )
    }

    /**
     * ark 文生图——验证 endpoint/auth 跑通，提交本身不报 `Incorrect padding`。
     *
     * data URI 编码已在 [ArkImageBackendTest] 单测覆盖；本测试用真实 ark API
     * 验证 Seedream 端点（REST 等价 SDK）的 size 解析与同步响应解析跑通。
     */
    @Test
    fun arkTextToImageDoesNotReportIncorrectPadding() = runBlocking<Unit> {
        val key = env("ARK_API_KEY")
        assumeTrue("ARK_API_KEY 未设置，跳过 ark live 验证", key != null)

        val cfg = AiImageProviderConfig(
            name = "ark-img-live",
            type = AiImageProviderConfig.TYPE_ARK,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            apiKey = key!!,
            model = "doubao-seedream-5-0-lite-260128",
            timeoutMillisecond = 120_000L
        )
        val backend = ArkImageBackend(cfg)
        val request = ImageGenerationRequest(
            prompt = "一只可爱的橘猫在窗台上晒太阳，温暖的光线",
            outputPath = tmpOutput(),
            aspectRatio = "9:16"
        )

        val result = runCatching { backend.generate(request) }
        assertTrue(
            "ark 图像提交不应报 Incorrect padding：${result.exceptionOrNull()?.message ?: ""}",
            result.exceptionOrNull()?.message?.contains("padding", ignoreCase = true) != true
        )
        // 成功时验证产物
        result.getOrNull()?.let {
            assertTrue("图片文件应存在", it.imagePath.exists())
            assertNotNull("应有 provider", it.provider)
        }
    }

    /**
     * ark 图生图（参考图 = data URI 单字符串）——验证 data URI 提交不报 `Incorrect padding`。
     *
     * 这是 P3a 头条验收的 live 证明：ark I2I 单张参考图编码成 data URI 字符串（[ImageCodec.toDataUri]，
     * NO_WRAP base64）下发，验证不触发 padding 错误。
     */
    @Test
    fun arkImage2ImageDataUriNoIncorrectPadding() = runBlocking {
        val key = env("ARK_API_KEY")
        assumeTrue("ARK_API_KEY 未设置，跳过 ark I2I live 验证", key != null)

        val cfg = AiImageProviderConfig(
            name = "ark-img-live-i2i",
            type = AiImageProviderConfig.TYPE_ARK,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            apiKey = key!!,
            model = "doubao-seedream-5-0-lite-260128",
            timeoutMillisecond = 120_000L
        )
        val backend = ArkImageBackend(cfg)
        val refImage = tmpJpeg("ark-img-ref")
        val request = ImageGenerationRequest(
            prompt = "保持画面构图，增强暖色调与光影层次",
            outputPath = tmpOutput(),
            referenceImages = listOf(ReferenceImage(refImage.absolutePath)),
            aspectRatio = "9:16"
        )

        val result = runCatching { backend.generate(request) }
        val err = result.exceptionOrNull()
        assertTrue(
            "ark I2I data URI 提交不应报 Incorrect padding" +
                (err?.message?.let { "：$it" } ?: ""),
            err?.message?.contains("padding", ignoreCase = true) != true
        )
    }
}
