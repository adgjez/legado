package io.legado.app.ui.main.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5 配置迁移验证（plan P5 验收清单）：
 * - 老 video type（openai/js/doubao）不在 [AiVideoProviderConfig.VALID_TYPES] 中 → normalize 丢弃
 * - 老 image type（js）不在 [AiImageProviderConfig.VALID_TYPES] 中 → normalize 丢弃
 * - 新 type 在 VALID_TYPES 中 → normalize 保留
 *
 * 直接测 VALID_TYPES 集合而非 AppConfig.normalize 私有函数：normalize 逻辑是
 * `it in VALID_TYPES ?: TYPE_ARK`，VALID_TYPES 内容即迁移行为的完整规约。
 * 纯 JVM 测试，不依赖 appCtx（避免 Robolectric 初始化问题）。
 */
class AppConfigMigrationTest {

    // ============================================================
    // video：老 type 丢弃
    // ============================================================

    @Test
    fun videoOldTypeOpenaiIsDroppedByNormalize() {
        assertFalse(
            "老 video type openai 应被 normalize 丢弃",
            "openai" in AiVideoProviderConfig.VALID_TYPES
        )
    }

    @Test
    fun videoOldTypeJsIsDroppedByNormalize() {
        assertFalse(
            "老 video type js 应被 normalize 丢弃",
            "js" in AiVideoProviderConfig.VALID_TYPES
        )
    }

    @Test
    fun videoOldTypeDoubaoIsDroppedByNormalize() {
        assertFalse(
            "老 video type doubao 应被 normalize 丢弃",
            "doubao" in AiVideoProviderConfig.VALID_TYPES
        )
    }

    // ============================================================
    // video：新 type 保留
    // ============================================================

    @Test
    fun videoNewTypesAreKeptByNormalize() {
        listOf(
            AiVideoProviderConfig.TYPE_ARK,
            AiVideoProviderConfig.TYPE_AGNES,
            AiVideoProviderConfig.TYPE_SORA,
            AiVideoProviderConfig.TYPE_VEO,
            AiVideoProviderConfig.TYPE_KLING,
            AiVideoProviderConfig.TYPE_NEWAPI,
            AiVideoProviderConfig.TYPE_V2,
            AiVideoProviderConfig.TYPE_DASHSCOPE,
            AiVideoProviderConfig.TYPE_MINIMAX,
            AiVideoProviderConfig.TYPE_VIDU,
            AiVideoProviderConfig.TYPE_GROK
        ).forEach { t ->
            assertTrue(
                "新 video type $t 应被 normalize 保留",
                t in AiVideoProviderConfig.VALID_TYPES
            )
        }
    }

    // ============================================================
    // image：老 type 丢弃
    // ============================================================

    @Test
    fun imageOldTypeJsIsDroppedByNormalize() {
        assertFalse(
            "老 image type js 应被 normalize 丢弃",
            "js" in AiImageProviderConfig.VALID_TYPES
        )
    }

    // ============================================================
    // image：新 type 保留（含 openai/agnes，P3 image backend 用）
    // ============================================================

    @Test
    fun imageNewTypesAreKeptByNormalize() {
        listOf(
            AiImageProviderConfig.TYPE_OPENAI,
            AiImageProviderConfig.TYPE_AGNES,
            AiImageProviderConfig.TYPE_ARK,
            AiImageProviderConfig.TYPE_DASHSCOPE,
            AiImageProviderConfig.TYPE_GEMINI,
            AiImageProviderConfig.TYPE_GROK,
            AiImageProviderConfig.TYPE_KLING,
            AiImageProviderConfig.TYPE_MINIMAX,
            AiImageProviderConfig.TYPE_VIDU
        ).forEach { t ->
            assertTrue(
                "新 image type $t 应被 normalize 保留",
                t in AiImageProviderConfig.VALID_TYPES
            )
        }
    }

    // ============================================================
    // 默认 type 是 TYPE_ARK（老配置回退目标）
    // ============================================================

    @Test
    fun videoDefaultTypeIsArk() {
        assertTrue(
            "video 默认 type 应是 TYPE_ARK（老配置回退目标）",
            AiVideoProviderConfig().type == AiVideoProviderConfig.TYPE_ARK
        )
    }

    @Test
    fun imageDefaultTypeIsArk() {
        assertTrue(
            "image 默认 type 应是 TYPE_ARK（老配置回退目标）",
            AiImageProviderConfig().type == AiImageProviderConfig.TYPE_ARK
        )
    }
}
