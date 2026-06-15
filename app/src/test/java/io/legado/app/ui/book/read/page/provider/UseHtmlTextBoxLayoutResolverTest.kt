package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UseHtmlTextBoxLayoutResolverTest {

    @Test
    fun widthPercentageUsesVisibleWidth() {
        val layout = resolve(style = "width:60%")

        assertEquals(600, layout?.width)
        assertEquals(0f, layout?.startOffset)
    }

    @Test
    fun widthPxKeepsAbsoluteWidth() {
        val layout = resolve(style = "width:240px")

        assertEquals(240, layout?.width)
        assertEquals(0f, layout?.startOffset)
    }

    @Test
    fun autoMarginsCenterExplicitWidth() {
        val layout = resolve(style = "width:60%;margin-left:auto;margin-right:auto")

        assertEquals(600, layout?.width)
        assertEquals(200f, layout?.startOffset)
    }

    @Test
    fun leftAutoRightMarginAlignsToRightSide() {
        val layout = resolve(style = "width:40%;margin-left:auto;margin-right:100px")

        assertEquals(400, layout?.width)
        assertEquals(500f, layout?.startOffset)
    }

    @Test
    fun maxWidthLimitsLayoutWidth() {
        val layout = resolve(style = "width:90%;max-width:500px")

        assertEquals(500, layout?.width)
        assertEquals(0f, layout?.startOffset)
    }

    @Test
    fun maxWidthWithAutoMarginsCentersBox() {
        val layout = resolve(style = "max-width:500px;margin-left:auto;margin-right:auto")

        assertEquals(500, layout?.width)
        assertEquals(250f, layout?.startOffset)
    }

    @Test
    fun marginZeroAloneDoesNotEnableTextBox() {
        assertNull(resolve(style = "margin:0"))
    }

    @Test
    fun oversizedWidthIsClampedToVisibleWidth() {
        val layout = resolve(style = "width:200%")

        assertEquals(1000, layout?.width)
        assertEquals(0f, layout?.startOffset)
    }

    @Test
    fun emptyStyleWithoutOptInDoesNotEnableTextBox() {
        assertNull(resolve(style = ""))
    }

    @Test
    fun explicitAttributeEnablesDefaultFullWidthBox() {
        val layout = resolve(
            attributes = mapOf("data-legado-layout" to "text-box"),
            style = ""
        )

        assertEquals(1000, layout?.width)
        assertEquals(0f, layout?.startOffset)
    }

    @Test
    fun dataWidthOverridesCssWidth() {
        val layout = resolve(
            attributes = mapOf("data-legado-width" to "50%"),
            style = "width:80%"
        )

        assertEquals(500, layout?.width)
    }

    private fun resolve(
        attributes: Map<String, String> = emptyMap(),
        style: String
    ): UseHtmlTextBoxLayout? {
        return UseHtmlTextBoxLayoutResolver.resolve(
            attributes = attributes,
            style = style,
            visibleWidth = 1000,
            emPx = 20f
        )
    }
}
