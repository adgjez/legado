package io.legado.app.model.localBook.epubcore.layout

import android.text.Layout
import io.legado.app.model.localBook.epubcore.style.EpubComputedStyle
import io.legado.app.model.localBook.epubcore.style.EpubTextAlign

fun EpubComputedStyle.toLayoutAlignment(default: Layout.Alignment): Layout.Alignment {
    return when (textAlign) {
        EpubTextAlign.Center -> Layout.Alignment.ALIGN_CENTER
        EpubTextAlign.End -> Layout.Alignment.ALIGN_OPPOSITE
        EpubTextAlign.Justify,
        EpubTextAlign.Start -> default
    }
}
