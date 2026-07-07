package io.legado.app.help.ai.backends.video

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.VideoGenerationRequest
import io.legado.app.help.ai.backends.compress.CompressedRef
import io.legado.app.help.ai.backends.compress.RefRole
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [V2VideoBackend] 编码形态 + 多路径容错提取 + 状态归一化 + with_start_frame 单测（P2c）。
 *
 * 核心验证：
 * - **参考图用 data URI**：首帧 `image_url`、尾帧 `last_image_url`、参考数组 `image_urls`
 * - with_start_frame=true：首帧与参考数组为共存字段（同 body 同时出现）
 * - 多路径容错提取：VIDEO_URL_PATHS / TASK_ID_PATHS / STATUS_PATHS 三张优先级表
 * - normalizeStatus：覆盖 aimlapi 官方枚举 + 跨厂商同义词；未知串当 running
 * - normalizeRoot：补 https:// + 去尾斜杠 + 去末尾版本段（/v1、/v2beta）
 * - 能力值：all true, max=4, withStartFrame=true
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class V2VideoBackendTest {

    private fun newBackend(model: String = "kling-v1", baseUrl: String = "https://api.aimlapi.com"): V2VideoBackend {
        val cfg = AiVideoProviderConfig(
            name = "v2-test",
            type = AiVideoProviderConfig.TYPE_V2,
            baseUrl = baseUrl,
            apiKey = "test-key",
            model = model,
            submitUrl = "",
            pollUrlTemplate = ""
        )
        return V2VideoBackend(cfg)
    }

    private fun tmpJpeg(name: String): File {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            0, 1, 0, 0, 1, 0, 1, 0, 0,
            0xFF.toByte(), 0xD9.toByte()
        )
        return File.createTempFile(name, ".jpg").apply { writeBytes(bytes); deleteOnExit() }
    }

    // ==================== 能力值 ====================

    @Test
    fun videoCapabilitiesForModelAllTrueMax4WithStartFrame() {
        val caps = V2VideoBackend.videoCapabilitiesForModel("any-model")
        assertTrue("firstFrame 应 true", caps.firstFrame)
        assertTrue("lastFrame 应 true", caps.lastFrame)
        assertTrue("referenceImages 应 true", caps.referenceImages)
        assertEquals("maxReferenceImages 应 4", 4, caps.maxReferenceImages)
        assertTrue("withStartFrame 应 true（首帧与参考数组共存）",
            caps.referenceImagesWithStartFrame)
    }

    // ==================== data URI 编码形态 ====================

    @Test
    fun buildRequestBodyUsesDataUriForFirstFrame() {
        val backend = newBackend("kling-v1")
        val firstFrame = tmpJpeg("v2ff")
        val request = VideoGenerationRequest(
            prompt = "首帧",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame
        )
        val compressed = listOf(CompressedRef(firstFrame, "first_frame", RefRole.FRAME))
        val body = backend.buildRequestBody(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 image_url 字段", root.has("image_url"))
        val v = root.get("image_url").asString
        assertTrue("image_url 应是 data URI，实际：$v", v.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun buildRequestBodyUsesDataUriForLastFrame() {
        val backend = newBackend("kling-v1")
        val lastFrame = tmpJpeg("v2lf")
        val request = VideoGenerationRequest(
            prompt = "尾帧",
            outputPath = File("/tmp/out.mp4"),
            endImage = lastFrame
        )
        val compressed = listOf(CompressedRef(lastFrame, "last_frame", RefRole.FRAME))
        val body = backend.buildRequestBody(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 last_image_url 字段", root.has("last_image_url"))
        assertTrue("last_image_url 应是 data URI",
            root.get("last_image_url").asString.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun buildRequestBodyUsesDataUriForReferenceImagesArray() {
        val backend = newBackend("kling-v1")
        val ref1 = tmpJpeg("v2r1")
        val ref2 = tmpJpeg("v2r2")
        val request = VideoGenerationRequest(
            prompt = "参考数组",
            outputPath = File("/tmp/out.mp4"),
            referenceImages = listOf(ref1, ref2)
        )
        val compressed = listOf(
            CompressedRef(ref1, "ref_0", RefRole.ARRAY),
            CompressedRef(ref2, "ref_1", RefRole.ARRAY)
        )
        val body = backend.buildRequestBody(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 image_urls 数组", root.has("image_urls"))
        val arr = root.getAsJsonArray("image_urls")
        assertEquals(2, arr.size())
        for (i in 0 until arr.size()) {
            val v = arr[i].asString
            assertTrue("image_urls[$i] 应是 data URI，实际：$v",
                v.startsWith("data:image/jpeg;base64,"))
        }
    }

    @Test
    fun buildRequestBodyWithStartFrameCoexistsWithReferenceImages() {
        // with_start_frame=true：首帧 + 参考数组同 body 共存（与 newapi 仅首帧不同）
        val backend = newBackend("kling-v1")
        val firstFrame = tmpJpeg("v2ff")
        val ref1 = tmpJpeg("v2r1")
        val request = VideoGenerationRequest(
            prompt = "共存",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame,
            referenceImages = listOf(ref1)
        )
        val compressed = listOf(
            CompressedRef(firstFrame, "first_frame", RefRole.FRAME),
            CompressedRef(ref1, "ref_0", RefRole.ARRAY)
        )
        val body = backend.buildRequestBody(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应同时有 image_url（首帧）", root.has("image_url"))
        assertTrue("应同时有 image_urls（参考数组）", root.has("image_urls"))
        assertEquals("参考数组应只有 1 个（首帧不在内）", 1, root.getAsJsonArray("image_urls").size())
    }

    @Test
    fun buildRequestBodyIncludesRequiredFields() {
        val backend = newBackend("kling-v1")
        val request = VideoGenerationRequest(
            prompt = "hello",
            outputPath = File("/tmp/out.mp4"),
            aspectRatio = "16:9",
            durationSeconds = 5,
            resolution = "1080",
            seed = 42L
        )
        val body = backend.buildRequestBody(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals("kling-v1", root.get("model").asString)
        assertEquals("hello", root.get("prompt").asString)
        assertEquals("duration 应 5", 5, root.get("duration").asInt)
        assertEquals("16:9", root.get("aspect_ratio").asString)
        assertEquals("1080", root.get("resolution").asString)
        assertEquals(42L, root.get("seed").asLong)
    }

    // ==================== normalizeStatus ====================

    @Test
    fun normalizeStatusCompletedMapsToSucceeded() {
        assertEquals("succeeded", V2VideoBackend.normalizeStatus("completed"))
        assertEquals("succeeded", V2VideoBackend.normalizeStatus("SUCCEEDED"))
        assertEquals("succeeded", V2VideoBackend.normalizeStatus("succeed"))
        assertEquals("succeeded", V2VideoBackend.normalizeStatus("success"))
    }

    @Test
    fun normalizeStatusFailedVariantsMapToFailed() {
        assertEquals("failed", V2VideoBackend.normalizeStatus("failed"))
        assertEquals("failed", V2VideoBackend.normalizeStatus("fail"))
        assertEquals("failed", V2VideoBackend.normalizeStatus("error"))
        assertEquals("failed", V2VideoBackend.normalizeStatus("expired"))
        assertEquals("failed", V2VideoBackend.normalizeStatus("canceled"))
        assertEquals("failed", V2VideoBackend.normalizeStatus("cancelled"))
    }

    @Test
    fun normalizeStatusRunningVariantsMapToRunning() {
        assertEquals("running", V2VideoBackend.normalizeStatus("generating"))
        assertEquals("running", V2VideoBackend.normalizeStatus("in_progress"))
        assertEquals("running", V2VideoBackend.normalizeStatus("running"))
        assertEquals("running", V2VideoBackend.normalizeStatus("processing"))
    }

    @Test
    fun normalizeStatusQueuedVariantsMapToQueued() {
        assertEquals("queued", V2VideoBackend.normalizeStatus("queued"))
        assertEquals("queued", V2VideoBackend.normalizeStatus("queueing"))
        assertEquals("queued", V2VideoBackend.normalizeStatus("preparing"))
        assertEquals("queued", V2VideoBackend.normalizeStatus("submitted"))
        assertEquals("queued", V2VideoBackend.normalizeStatus("pending"))
        assertEquals("queued", V2VideoBackend.normalizeStatus("created"))
    }

    @Test
    fun normalizeStatusUnknownDefaultsToRunning() {
        // 未知串当 running（继续轮询而非误判终态）
        assertEquals("running", V2VideoBackend.normalizeStatus("weird-status-xyz"))
        assertEquals("running", V2VideoBackend.normalizeStatus(""))
        assertEquals("running", V2VideoBackend.normalizeStatus(null))
        assertEquals("running", V2VideoBackend.normalizeStatus("   "))
    }

    // ==================== dig / firstStrByPaths 多路径容错 ====================

    @Test
    fun digWalksObjectKeysAndArrayIndices() {
        val payload = JsonObject().apply {
            add("data", JsonObject().apply {
                add("videos", JsonArray().apply {
                    add(JsonObject().apply { addProperty("url", "https://x.com/v.mp4") })
                })
            })
        }
        val url = V2VideoBackend.dig(payload, "data", "videos", 0, "url")
        assertEquals("https://x.com/v.mp4", url?.asString)
    }

    @Test
    fun digReturnsNullForMissingPath() {
        val payload = JsonObject().apply { addProperty("foo", "bar") }
        assertNull(V2VideoBackend.dig(payload, "missing"))
        assertNull(V2VideoBackend.dig(payload, "foo", "deep"))
        assertNull(V2VideoBackend.dig(null, "anything"))
    }

    @Test
    fun firstStrByPathsReturnsFirstHitByPriority() {
        // data.task_result.videos[0].url（path 5）优先于顶层 url（path 6）——
        // 优先级表按声明顺序逐个试取，kling 风格回包的深路径先于通用顶层 url
        val payload = JsonObject().apply {
            addProperty("url", "https://top.com/v.mp4")
            add("data", JsonObject().apply {
                add("task_result", JsonObject().apply {
                    add("videos", JsonArray().apply {
                        add(JsonObject().apply { addProperty("url", "https://deep.com/v.mp4") })
                    })
                })
            })
        }
        val url = V2VideoBackend.firstStrByPaths(payload, V2VideoBackend.VIDEO_URL_PATHS)
        assertEquals("应取优先级更高的 data.task_result.videos[0].url", "https://deep.com/v.mp4", url)
    }

    @Test
    fun firstStrByPathsFallsBackToDeepPath() {
        // 顶层无 url，应回退到 data.task_result.videos[0].url（kling 风格回包）
        val payload = JsonObject().apply {
            add("data", JsonObject().apply {
                add("task_result", JsonObject().apply {
                    add("videos", JsonArray().apply {
                        add(JsonObject().apply { addProperty("url", "https://deep.com/v.mp4") })
                    })
                })
            })
        }
        val url = V2VideoBackend.firstStrByPaths(payload, V2VideoBackend.VIDEO_URL_PATHS)
        assertEquals("https://deep.com/v.mp4", url)
    }

    @Test
    fun firstStrByPathsTaskIdPriority() {
        // generation_id 优先于 id/task_id/data.task_id 等
        val payloadA = JsonObject().apply { addProperty("generation_id", "gen-1") }
        assertEquals("gen-1", V2VideoBackend.firstStrByPaths(payloadA, V2VideoBackend.TASK_ID_PATHS))

        // 缺 generation_id 时取 id
        val payloadB = JsonObject().apply { addProperty("id", "id-1") }
        assertEquals("id-1", V2VideoBackend.firstStrByPaths(payloadB, V2VideoBackend.TASK_ID_PATHS))

        // 嵌套 data.task_id（某些中转站风格）
        val payloadC = JsonObject().apply {
            add("data", JsonObject().apply { addProperty("task_id", "task-1") })
        }
        assertEquals("task-1", V2VideoBackend.firstStrByPaths(payloadC, V2VideoBackend.TASK_ID_PATHS))
    }

    @Test
    fun firstStrByPathsReturnsNullWhenNoPathHits() {
        val payload = JsonObject().apply { addProperty("unrelated", "x") }
        assertNull(V2VideoBackend.firstStrByPaths(payload, V2VideoBackend.VIDEO_URL_PATHS))
    }

    // ==================== normalizeRoot ====================

    @Test
    fun normalizeRootStripsTrailingSlash() {
        assertEquals("https://api.x.com", V2VideoBackend.normalizeRoot("https://api.x.com/"))
        assertEquals("https://api.x.com", V2VideoBackend.normalizeRoot("https://api.x.com///"))
    }

    @Test
    fun normalizeRootAddsHttpsScheme() {
        assertEquals("https://api.x.com", V2VideoBackend.normalizeRoot("api.x.com"))
        assertEquals("https://api.x.com", V2VideoBackend.normalizeRoot("api.x.com/"))
    }

    @Test
    fun normalizeRootStripsTrailingVersionSegment() {
        // 去末尾 /v1 /v2 /v2beta /v1.0
        assertEquals("https://api.x.com", V2VideoBackend.normalizeRoot("https://api.x.com/v1"))
        assertEquals("https://api.x.com", V2VideoBackend.normalizeRoot("https://api.x.com/v2beta"))
        assertEquals("https://api.x.com", V2VideoBackend.normalizeRoot("https://api.x.com/v1.0"))
        assertEquals("https://api.x.com", V2VideoBackend.normalizeRoot("https://api.x.com/v2/"))
    }

    @Test
    fun normalizeRootPreservesPathBeforeVersion() {
        // 路径中间的版本段不去，只去末尾
        assertEquals("https://api.x.com/foo/v1", V2VideoBackend.normalizeRoot("https://api.x.com/foo/v1/v2"))
    }

    @Test
    fun normalizeRootEmptyInputReturnsEmpty() {
        assertEquals("", V2VideoBackend.normalizeRoot(""))
        assertEquals("", V2VideoBackend.normalizeRoot("   "))
    }

    // ==================== typeId / model 默认值 ====================

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("v2", backend.typeId)
        assertEquals("空 model 应用默认值", "kling-v1", backend.model)
    }
}
