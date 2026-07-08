package io.legado.app.help.ai.scheduling

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NonResumableProviders] 单测。
 */
class NonResumableProvidersTest {

    @Test
    fun grokIsNonResumable() {
        assertTrue(NonResumableProviders.isNonResumable("grok"))
        assertTrue(NonResumableProviders.isNonResumable("GROK"))
    }

    @Test
    fun viduIsNonResumable() {
        assertTrue(NonResumableProviders.isNonResumable("vidu"))
    }

    @Test
    fun resumableProvidersReturnFalse() {
        assertFalse(NonResumableProviders.isNonResumable("agnes"))
        assertFalse(NonResumableProviders.isNonResumable("ark"))
        assertFalse(NonResumableProviders.isNonResumable("gemini"))
        assertFalse(NonResumableProviders.isNonResumable("kling"))
    }

    @Test
    fun nullOrBlankReturnsFalse() {
        assertFalse(NonResumableProviders.isNonResumable(null))
        assertFalse(NonResumableProviders.isNonResumable(""))
        assertFalse(NonResumableProviders.isNonResumable("   "))
    }

    @Test
    fun decoratedProviderSubstringMatches() {
        // 容忍代理中转的前后缀装饰（子串匹配）
        assertTrue(NonResumableProviders.isNonResumable("proxy-grok-v1"))
        assertTrue(NonResumableProviders.isNonResumable("vidu-pro"))
    }
}
