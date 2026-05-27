package io.legado.app.ui.main.compose.readrecord

import androidx.compose.runtime.Immutable

@Immutable
data class ReadRecordUiState(
    val title: String = "阅读记录",
    val subtitle: String = "",
    val overview: List<ReadRecordMetricUi> = emptyList(),
    val goal: ReadRecordGoalUi? = null,
    val heatmap: List<ReadRecordHeatmapCellUi> = emptyList(),
    val recentBooks: List<ReadRecordBookUi> = emptyList(),
    val rankItems: List<ReadRecordRankUi> = emptyList(),
    val dailyRecords: List<ReadRecordDailyUi> = emptyList(),
    val componentOrder: List<ReadRecordComponentUi> = ReadRecordComponentUi.entries,
    val emptyHint: String = "暂无阅读记录"
) {
    val hasContent: Boolean
        get() = overview.isNotEmpty() ||
            goal != null ||
            heatmap.isNotEmpty() ||
            recentBooks.isNotEmpty() ||
            rankItems.isNotEmpty() ||
            dailyRecords.isNotEmpty()
}

@Immutable
data class ReadRecordMetricUi(
    val key: String,
    val label: String,
    val value: String,
    val helper: String = ""
)

@Immutable
data class ReadRecordGoalUi(
    val title: String = "今日目标",
    val userName: String = "",
    val progress: Float = 0f,
    val currentText: String,
    val targetText: String,
    val helper: String = "",
    val actionText: String = "编辑"
) {
    val normalizedProgress: Float
        get() = progress.coerceIn(0f, 1f)
}

@Immutable
data class ReadRecordHeatmapCellUi(
    val key: String,
    val level: Float,
    val contentDescription: String = ""
) {
    val normalizedLevel: Float
        get() = level.coerceIn(0f, 1f)
}

@Immutable
data class ReadRecordBookUi(
    val id: String,
    val title: String,
    val author: String = "",
    val progressText: String = "",
    val lastReadText: String = "",
    val readTimeText: String = ""
)

@Immutable
data class ReadRecordRankUi(
    val id: String,
    val title: String,
    val author: String = "",
    val readTimeText: String,
    val percent: Float = 0f
) {
    val normalizedPercent: Float
        get() = percent.coerceIn(0f, 1f)
}

@Immutable
data class ReadRecordDailyUi(
    val id: String,
    val dateText: String,
    val readTimeText: String,
    val bookCountText: String = ""
)

enum class ReadRecordComponentUi {
    Goal,
    Overview,
    Heatmap,
    RecentBooks,
    Ranking,
    DailyRecords
}

@Immutable
data class ReadRecordActions(
    val onOpenBook: (ReadRecordBookUi) -> Unit = {},
    val onBookLongClick: (ReadRecordBookUi) -> Unit = {},
    val onRankItemClick: (ReadRecordRankUi) -> Unit = {},
    val onShowAllRank: () -> Unit = {},
    val onGoalClick: () -> Unit = {},
    val onDateClick: () -> Unit = {},
    val onMoreClick: () -> Unit = {}
)
