package io.legado.app.ui.main.ai.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class AiProcessChainStep(
    val id: String,
    val type: AiProcessStepType,
    val title: String,
    val subtitle: String,
    val detail: String,
    val pending: Boolean,
    val success: Boolean,
    val collapsed: Boolean
)

enum class AiProcessStepType {
    Thinking,
    Tool
}

@Composable
fun AiProcessTimelineCard(
    steps: List<AiProcessStepUi>,
    style: AiComposeStyle,
    modifier: Modifier = Modifier,
    onToolClick: (AiProcessStepUi) -> Unit = {}
) {
    if (steps.isEmpty()) return
    var expanded by remember(steps.first().id) { mutableStateOf(false) }
    var expandedStepIds by remember(steps.first().id) { mutableStateOf(emptySet<String>()) }
    val activeStep = steps.firstOrNull { it.pending } ?: steps.last()
    val hasFailedStep = steps.any { !it.success }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.metrics.cardRadius),
        color = style.colors.toolSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(
            style.metrics.strokeWidth,
            if (hasFailedStep) style.colors.danger.copy(alpha = 0.28f) else style.colors.stroke
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(style.metrics.chipRadius))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepCountChip(count = steps.size, style = style)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeStep.title,
                        color = style.colors.primaryText,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    activeStep.subtitle.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = style.colors.secondaryText,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                if (activeStep.pending) {
                    ProcessChip(text = "...", style = style)
                } else {
                    ChevronIcon(
                        expanded = expanded,
                        tint = style.colors.secondaryText,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            if (expanded) {
                MiniStepPolyline(
                    steps = steps,
                    style = style,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp)
                        .height(26.dp)
                )
                steps.forEachIndexed { index, step ->
                    val stepExpanded = step.pending || !step.collapsed || step.id in expandedStepIds
                    AiProcessTimelineRow(
                        step = step,
                        expanded = stepExpanded,
                        isLast = index == steps.lastIndex,
                        style = style,
                        onClick = {
                            if (step.type == AiProcessStepType.Tool && step.payload != null) {
                                onToolClick(step)
                            } else if (!step.pending) {
                                expandedStepIds = if (step.id in expandedStepIds) {
                                    expandedStepIds - step.id
                                } else {
                                    expandedStepIds + step.id
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StepCountChip(count: Int, style: AiComposeStyle) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(style.metrics.chipRadius))
            .background(style.colors.accent.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            text = "$count 步",
            color = style.colors.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MiniStepPolyline(
    steps: List<AiProcessStepUi>,
    style: AiComposeStyle,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (steps.isEmpty()) return@Canvas
        val gap = if (steps.size <= 1) 0f else size.width / (steps.size - 1)
        val yLow = size.height * 0.68f
        val yHigh = size.height * 0.32f
        val points = steps.mapIndexed { index, step ->
            Offset(
                x = if (steps.size == 1) size.width / 2f else gap * index,
                y = if (step.type == AiProcessStepType.Tool) yHigh else yLow
            )
        }
        points.zipWithNext().forEach { (start, end) ->
            drawLine(
                color = style.colors.stroke,
                start = start,
                end = end,
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        points.forEachIndexed { index, point ->
            val step = steps[index]
            drawCircle(
                color = when {
                    !step.success -> style.colors.danger
                    step.pending -> style.colors.accent
                    step.type == AiProcessStepType.Tool -> style.colors.accent.copy(alpha = 0.72f)
                    else -> style.colors.secondaryText.copy(alpha = 0.56f)
                },
                radius = if (step.pending) 4.2.dp.toPx() else 3.2.dp.toPx(),
                center = point
            )
        }
    }
}

@Composable
private fun AiProcessTimelineRow(
    step: AiProcessStepUi,
    expanded: Boolean,
    isLast: Boolean,
    style: AiComposeStyle,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.chipRadius))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        TimelineMarker(
            step = AiProcessChainStep(
                id = step.id,
                type = step.type,
                title = step.title,
                subtitle = step.subtitle,
                detail = step.detail,
                pending = step.pending,
                success = step.success,
                collapsed = step.collapsed
            ),
            isLast = isLast,
            style = style
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = step.title,
                    color = style.colors.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (step.pending) {
                    ProcessChip(text = "...", style = style)
                } else {
                    ChevronIcon(
                        expanded = expanded,
                        tint = style.colors.secondaryText,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            if (!expanded && step.subtitle.isNotBlank()) {
                Text(
                    text = step.subtitle,
                    color = style.colors.secondaryText,
                    fontSize = 12.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            if (expanded && step.detail.isNotBlank() && step.type != AiProcessStepType.Tool) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .clip(RoundedCornerShape(style.metrics.chipRadius))
                        .background(style.colors.primaryText.copy(alpha = 0.045f))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = step.detail.trim(),
                            color = style.colors.secondaryText,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AiProcessChainCard(
    steps: List<AiProcessChainStep>,
    expandedStepIds: Set<String>,
    onToggleStep: (String) -> Unit,
    style: AiComposeStyle,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty()) return
    val hasFailedStep = steps.any { !it.success }
    val surface = if (steps.any { it.type == AiProcessStepType.Tool }) {
        style.colors.toolSurface
    } else {
        style.colors.processSurface
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.metrics.cardRadius),
        color = surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(
            style.metrics.strokeWidth,
            if (hasFailedStep) style.colors.danger.copy(alpha = 0.28f) else style.colors.stroke
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            steps.forEachIndexed { index, step ->
                val expanded = step.pending || !step.collapsed || step.id in expandedStepIds
                AiProcessStepRow(
                    step = step,
                    expanded = expanded,
                    isLast = index == steps.lastIndex,
                    style = style,
                    onToggle = {
                        if (!step.pending) {
                            onToggleStep(step.id)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AiProcessStepRow(
    step: AiProcessChainStep,
    expanded: Boolean,
    isLast: Boolean,
    style: AiComposeStyle,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.chipRadius))
            .clickable(enabled = !step.pending, onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        TimelineMarker(
            step = step,
            isLast = isLast,
            style = style
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = step.title,
                    color = style.colors.primaryText,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (step.pending) {
                    ProcessChip(text = "...", style = style)
                } else {
                    ChevronIcon(
                        expanded = expanded,
                        tint = style.colors.secondaryText,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .size(14.dp)
                    )
                }
            }
            val summary = step.subtitle.ifBlank { step.detail.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
            if (!expanded && summary.isNotBlank()) {
                Text(
                    text = summary,
                    color = style.colors.secondaryText,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (expanded && step.detail.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .clip(RoundedCornerShape(style.metrics.chipRadius))
                        .background(style.colors.primaryText.copy(alpha = 0.045f))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = step.detail.trim(),
                            color = style.colors.secondaryText,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineMarker(
    step: AiProcessChainStep,
    isLast: Boolean,
    style: AiComposeStyle
) {
    val markerColor = when {
        !step.success -> style.colors.danger
        step.type == AiProcessStepType.Tool -> style.colors.accent
        else -> style.colors.secondaryText.copy(alpha = 0.72f)
    }
    Column(
        modifier = Modifier.width(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(if (step.pending) 10.dp else 8.dp)
                .background(markerColor, CircleShape)
        )
        if (!isLast) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .width(1.dp)
                    .height(32.dp)
                    .background(style.colors.stroke.copy(alpha = 0.72f))
            )
        }
    }
}

@Composable
private fun ChevronIcon(
    expanded: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(
            width = 1.8.dp.toPx(),
            cap = StrokeCap.Round
        )
        val left = Offset(size.width * 0.24f, size.height * if (expanded) 0.38f else 0.28f)
        val center = Offset(size.width * 0.50f, size.height * if (expanded) 0.64f else 0.50f)
        val right = Offset(size.width * 0.76f, size.height * if (expanded) 0.38f else 0.72f)
        drawLine(tint, left, center, strokeWidth = stroke.width, cap = stroke.cap)
        drawLine(tint, center, right, strokeWidth = stroke.width, cap = stroke.cap)
    }
}

@Composable
private fun ProcessChip(text: String, style: AiComposeStyle) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(style.metrics.chipRadius))
            .background(style.colors.accent.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = style.colors.accent,
            fontSize = 11.sp,
            lineHeight = 13.sp
        )
    }
}
