package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AiVideoService.buildSubmitUrl] 从 pollUrlTemplate 反推 origin 的兜底逻辑测试。
 *
 * 场景：视频 Provider 编辑页无 baseUrl 字段（save 时强制空），
 * 用户把 submitUrl 填成相对路径（如 /videos）时，从 pollUrlTemplate
 * 提取 origin（scheme://host[:port]）拼接。
 */
class AiVideoServiceBuildSubmitUrlTest {

    @Test
    fun doubaoRelativeSubmitUrlInfersOriginFromPollUrl() {
        // 豆包预置：pollUrl=https://ark.../tasks/{taskId}, submitUrl=/videos
        // 应反推出 https://ark.cn-beijing.volces.com 拼成 .../videos
        val provider = AiVideoProviderConfig(
            name = "doubao",
            type = AiVideoProviderConfig.TYPE_DOUBAO,
            submitUrl = "/videos",
            pollUrlTemplate = "https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks/{taskId}",
            baseUrl = ""
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/videos",
            AiVideoService.buildSubmitUrl(provider)
        )
    }

    @Test
    fun agnesRelativeSubmitUrlInfersOriginFromPollUrl() {
        val provider = AiVideoProviderConfig(
            name = "agnes",
            type = AiVideoProviderConfig.TYPE_AGNES,
            submitUrl = "/v1/videos",
            pollUrlTemplate = "https://apihub.agnes-ai.com/agnesapi?video_id={taskId}",
            baseUrl = ""
        )
        assertEquals(
            "https://apihub.agnes-ai.com/v1/videos",
            AiVideoService.buildSubmitUrl(provider)
        )
    }

    @Test
    fun fullUrlSubmitUrlUsedAsIs() {
        val provider = AiVideoProviderConfig(
            name = "x",
            submitUrl = "https://example.com/api/videos",
            pollUrlTemplate = "https://other.com/poll/{taskId}"
        )
        assertEquals(
            "https://example.com/api/videos",
            AiVideoService.buildSubmitUrl(provider)
        )
    }

    @Test
    fun emptySubmitUrlInfersFromPollUrlAndAppendsVideos() {
        // submitUrl 空 + baseUrl 空，从 pollUrl 反推 origin
        val provider = AiVideoProviderConfig(
            name = "x",
            submitUrl = "",
            baseUrl = "",
            pollUrlTemplate = "https://api.example.com/v1/tasks/{taskId}"
        )
        // pollUrl origin = https://api.example.com（不含 /v1），走 else 分支：origin + /v1/videos
        assertEquals(
            "https://api.example.com/v1/videos",
            AiVideoService.buildSubmitUrl(provider)
        )
    }

    @Test
    fun inferOriginFromPollUrlExtractsSchemeHostPort() {
        assertEquals(
            "https://ark.cn-beijing.volces.com",
            AiVideoService.inferOriginFromPollUrl("https://ark.cn-beijing.volces.com/api/v3/tasks/{taskId}")
        )
        assertEquals(
            "http://localhost:8080",
            AiVideoService.inferOriginFromPollUrl("http://localhost:8080/poll/{taskId}")
        )
        assertEquals(
            "https://api.example.com",
            AiVideoService.inferOriginFromPollUrl("https://api.example.com")
        )
    }

    @Test
    fun inferOriginFromPollUrlReturnsEmptyForNonHttp() {
        assertEquals("", AiVideoService.inferOriginFromPollUrl("/relative/path/{taskId}"))
        assertEquals("", AiVideoService.inferOriginFromPollUrl(""))
    }
}
