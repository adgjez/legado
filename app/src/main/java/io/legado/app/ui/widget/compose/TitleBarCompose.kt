package io.legado.app.ui.widget.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.legado.app.lib.theme.titleTypeface

@Immutable
data class TitleBarTitleState(
    val title: String,
    val subtitle: String,
    val titleColor: Int,
    val subtitleColor: Int,
    val titleSizeSp: Float,
    val subtitleSizeSp: Float
)

@Composable
fun ComposeTitleBarTitle(
    state: TitleBarTitleState,
    modifier: Modifier = Modifier
) {
    if (state.title.isBlank() && state.subtitle.isBlank()) return
    val context = LocalContext.current
    val titleFont = remember(context) { FontFamily(context.titleTypeface()) }
    Column(
        modifier = modifier
    ) {
        if (state.title.isNotBlank()) {
            Text(
                text = state.title,
                color = Color(state.titleColor),
                fontSize = state.titleSizeSp.sp,
                fontFamily = titleFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (state.subtitle.isNotBlank()) {
            Text(
                text = state.subtitle,
                color = Color(state.subtitleColor),
                fontSize = state.subtitleSizeSp.sp,
                fontFamily = titleFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
