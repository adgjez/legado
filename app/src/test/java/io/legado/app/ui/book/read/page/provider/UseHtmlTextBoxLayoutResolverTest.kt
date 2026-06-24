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
    fun fullWidthPercentageKeepsReaderLeftEdge() {
        val layout = resolve(style = "width:100%;text-align:left")

        assertEquals(1000, layout?.width)
        assertEquals(0f, layout?.startOffset)
    }

    @Test
    fun fullWidthWithZeroMarginKeepsReaderLeftEdge() {
        val layout = resolve(style = "margin:0;width:100%")

        assertEquals(1000, layout?.width)
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
        assertEquals(497f, layout?.startOffset)
    }

    @Test
    fun rightAlignedBoxKeepsSmallRightEdgeInset() {
        val layout = resolve(style = "width:60%;margin-left:auto;text-align:right")

        assertEquals(600, layout?.width)
        assertEquals(397f, layout?.startOffset)
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
    fun explicitAttributeFullWidthKeepsReaderLeftEdge() {
        val layout = resolve(
            attributes = mapOf("data-legado-layout" to "text-box"),
            style = "width:100%;margin-left:0;margin-right:0"
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

    @Test
    fun contentBoxAddsPaddingAndBorderToOuterWidth() {
        val box = resolveBox(
            style = "width:72%;padding:.1em .1em;border:1px solid #000;background:#eee"
        )

        assertEquals(720f, box?.contentWidth?.toFloat())
        assertEquals(726f, box?.borderBoxWidth)
        assertEquals(3f, box?.contentStartOffset)
        assertEquals(2f, box?.padding?.left)
        assertEquals(2f, box?.padding?.top)
        assertEquals(1f, box?.borderWidth)
    }

    @Test
    fun borderBoxKeepsDeclaredOuterWidth() {
        val box = resolveBox(
            style = "box-sizing:border-box;width:72%;padding:.5em 1em;border:2px solid #000"
        )

        assertEquals(720f, box?.borderBoxWidth)
        assertEquals(676, box?.contentWidth)
        assertEquals(22f, box?.contentStartOffset)
    }

    @Test
    fun rightBubbleUsesBorderBoxForRightAlignment() {
        val box = resolveBox(
            style = "width:72%;margin-left:auto;padding:.5em 1em;background:#eee"
        )

        assertEquals(760f, box?.borderBoxWidth)
        assertEquals(237f, box?.borderBoxStartOffset)
        assertEquals(257f, box?.contentStartOffset)
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

    private fun resolveBox(
        attributes: Map<String, String> = emptyMap(),
        style: String
    ): UseHtmlBoxStyle? {
        return UseHtmlTextBoxLayoutResolver.resolveBox(
            attributes = attributes,
            style = style,
            visibleWidth = 1000,
            emPx = 20f,
            backgroundColor = 0xffeeeeee.toInt(),
            borderColor = 0xff000000.toInt(),
            borderWidth = if (style.contains("border:2px")) 2f else if (style.contains("border:")) 1f else 0f,
            borderRadius = 0f
        )
    }
}
