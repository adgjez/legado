package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.widget.compose.AppDialogSliderRow
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.postEvent

class PaddingConfigDialog : ComposeDialogFragment() {

    override val widthFraction: Float = 0.9f

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = window.attributes
            attr.dimAmount = 0f
            window.attributes = attr
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    PaddingConfigContent(style = style)
                }
            }
        }
    }

    @Composable
    private fun PaddingConfigContent(style: AppDialogStyle) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HeaderSection(style = style)
                BodySection()
                FooterSection(style = style)
            }
        }
    }

    @Composable
    private fun HeaderSection(style: AppDialogStyle) {
        var showLine by rememberSaveable { mutableStateOf(ReadBookConfig.showHeaderLine) }
        var top by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingTop) }
        var bottom by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingBottom) }
        var left by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingLeft) }
        var right by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingRight) }

        PaddingSectionTitle(
            title = stringResource(R.string.header),
            showLine = showLine,
            style = style,
            onShowLineChange = {
                showLine = it
                ReadBookConfig.showHeaderLine = it
                postHeaderFooterChanged()
            }
        )
        PaddingSlider(
            title = stringResource(R.string.padding_top),
            value = top,
            range = 0..100,
            onValueChange = {
                top = it
                ReadBookConfig.headerPaddingTop = it
                postHeaderFooterChanged()
            }
        )
        PaddingSlider(
            title = stringResource(R.string.padding_bottom),
            value = bottom,
            range = 0..100,
            onValueChange = {
                bottom = it
                ReadBookConfig.headerPaddingBottom = it
                postHeaderFooterChanged()
            }
        )
        PaddingSlider(
            title = stringResource(R.string.padding_left),
            value = left,
            range = 0..100,
            onValueChange = {
                left = it
                ReadBookConfig.headerPaddingLeft = it
                postHeaderFooterChanged()
            }
        )
        PaddingSlider(
            title = stringResource(R.string.padding_right),
            value = right,
            range = 0..100,
            onValueChange = {
                right = it
                ReadBookConfig.headerPaddingRight = it
                postHeaderFooterChanged()
            }
        )
    }

    @Composable
    private fun BodySection() {
        var top by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingTop) }
        var bottom by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingBottom) }
        var left by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingLeft) }
        var right by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingRight) }

        PaddingSectionTitle(title = stringResource(R.string.main_body))
        PaddingSlider(
            title = stringResource(R.string.padding_top),
            value = top,
            range = 0..200,
            onValueChange = {
                top = it
                ReadBookConfig.paddingTop = it
                postBodyChanged()
            }
        )
        PaddingSlider(
            title = stringResource(R.string.padding_bottom),
            value = bottom,
            range = 0..100,
            onValueChange = {
                bottom = it
                ReadBookConfig.paddingBottom = it
                postBodyChanged()
            }
        )
        PaddingSlider(
            title = stringResource(R.string.padding_left),
            value = left,
            range = 0..100,
            onValueChange = {
                left = it
                ReadBookConfig.paddingLeft = it
                postBodyChanged()
            }
        )
        PaddingSlider(
            title = stringResource(R.string.padding_right),
            value = right,
            range = 0..100,
            onValueChange = {
                right = it
                ReadBookConfig.paddingRight = it
                postBodyChanged()
            }
        )
    }

    @Composable
    private fun FooterSection(style: AppDialogStyle) {
        var showLine by rememberSaveable { mutableStateOf(ReadBookConfig.showFooterLine) }
        var top by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingTop) }
        var bottom by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingBottom) }
        var left by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingLeft) }
        var right by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingRight) }

        PaddingSectionTitle(
            title = stringResource(R.string.footer),
            showLine = showLine,
            style = style,
            onShowLineChange = {
                showLine = it
                ReadBookConfig.showFooterLine = it
                postHeaderFooterChanged()
            }
        )
        PaddingSlider(
            title = stringResource(R.string.padding_top),
            value = top,
            range = 0..100,
            onValueChange = {
                top = it
                ReadBookConfig.footerPaddingTop = it
                postHeaderFooterChanged()
            }
        )
        PaddingSlider(
            title = stringResource(R.string.padding_bottom),
            value = bottom,
            range = 0..100,
            onValueChange = {
                bottom = it
                ReadBookConfig.footerPaddingBottom = it
                postHeaderFooterChanged()
            }
        )
        PaddingSlider(
            title = stringResource(R.string.padding_left),
            value = left,
            range = 0..100,
            onValueChange = {
                left = it
                ReadBookConfig.footerPaddingLeft = it
                postHeaderFooterChanged()
            }
        )
        PaddingSlider(
            title = stringResource(R.string.padding_right),
            value = right,
            range = 0..100,
            onValueChange = {
                right = it
                ReadBookConfig.footerPaddingRight = it
                postHeaderFooterChanged()
            }
        )
    }

    @Composable
    private fun PaddingSectionTitle(
        title: String,
        showLine: Boolean? = null,
        style: AppDialogStyle = rememberAppDialogStyle(),
        onShowLineChange: ((Boolean) -> Unit)? = null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = style.accent,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = style.titleFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (showLine != null && onShowLineChange != null) {
                Text(
                    text = stringResource(R.string.showLine),
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(10.dp))
                LegadoMiuixSwitch(
                    checked = showLine,
                    onCheckedChange = onShowLineChange,
                    palette = style.toMiuixPalette()
                )
            }
        }
    }

    @Composable
    private fun PaddingSlider(
        title: String,
        value: Int,
        range: IntRange,
        onValueChange: (Int) -> Unit
    ) {
        AppDialogSliderRow(
            title = title,
            value = value,
            range = range,
            onValueChange = { nextValue ->
                if (nextValue != value) {
                    onValueChange(nextValue)
                }
            }
        )
    }

    private fun postBodyChanged() {
        postEvent(EventBus.UP_CONFIG, arrayListOf(10, 5))
    }

    private fun postHeaderFooterChanged() {
        postEvent(EventBus.UP_CONFIG, arrayListOf(2))
    }
}
