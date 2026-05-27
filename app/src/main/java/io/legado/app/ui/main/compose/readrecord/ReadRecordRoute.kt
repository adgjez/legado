package io.legado.app.ui.main.compose.readrecord

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.ui.about.ReadRecordComponentType
import io.legado.app.ui.about.ReadRecordComponents
import io.legado.app.ui.about.ReadRecordWidgetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.max

@Composable
fun ReadRecordRoute(
    modifier: Modifier = Modifier,
    actions: ReadRecordActions = ReadRecordActions(),
    contentPadding: PaddingValues = PaddingValues(bottom = 92.dp),
    selectedDate: LocalDate = LocalDate.now(),
    refreshKey: Any? = Unit
) {
    val context = LocalContext.current
    val state by produceState(
        initialValue = ReadRecordUiState(subtitle = context.getString(R.string.read_record_stats_waiting)),
        key1 = selectedDate,
        key2 = refreshKey
    ) {
        value = withContext(Dispatchers.IO) {
            buildReadRecordUiState(context, selectedDate)
        }
    }
    ReadRecordScreen(
        state = state,
        modifier = modifier,
        actions = actions,
        contentPadding = contentPadding
    )
}

private fun buildReadRecordUiState(
    context: Context,
    selectedDate: LocalDate
): ReadRecordUiState {
    val allRecords = appDb.readRecordDao.allShow
    val totalTime = appDb.readRecordDao.allTime
    val dailyRecords = appDb.readRecordDailyDao.allDesc
    val dailyStats = dailyRecords.toDailySummaries()
    val dailyMap = dailyStats.associate { it.date to it.readTime }
    val month = YearMonth.from(selectedDate)
    val todayTime = dailyMap[selectedDate] ?: 0L
    val monthTime = dailyStats
        .filter { YearMonth.from(it.date) == month }
        .sumOf { it.readTime }
    val activeDays = dailyStats.count { it.readTime > 0L }
    val readBookCount = allRecords.size
    val readRecordMap = allRecords.associateBy { it.bookName }
    val recentBooks = appDb.readRecentBookDao.recentBooks(8)
        .map { book -> book.toReadRecordBookUi(context, readRecordMap[book.name]?.readTime ?: 0L) }
    val rankItems = buildRankItems()
    val heatmapCells = buildHeatmapCells(selectedDate, dailyMap)
    val goalConfig = ReadRecordWidgetStore.loadGoalConfig()
    val goalMs = goalConfig.dailyGoalMinutes * 60L * 1000L
    val headlineFormatter = DateTimeFormatter.ofPattern(
        context.getString(R.string.read_record_date_pattern),
        Locale.getDefault()
    )
    return ReadRecordUiState(
        title = "阅读记录",
        subtitle = if (dailyStats.isEmpty()) {
            context.getString(R.string.read_record_stats_waiting)
        } else {
            selectedDate.format(headlineFormatter)
        },
        overview = listOf(
            ReadRecordMetricUi(
                key = "today",
                label = if (selectedDate == LocalDate.now()) "今日" else "选中日期",
                value = context.formatDuration(todayTime)
            ),
            ReadRecordMetricUi(
                key = "month",
                label = "本月",
                value = context.formatDuration(monthTime)
            ),
            ReadRecordMetricUi(
                key = "total",
                label = "累计",
                value = context.formatDuration(totalTime)
            ),
            ReadRecordMetricUi(
                key = "activeDays",
                label = "活跃天数",
                value = context.getString(R.string.read_record_active_days_value, activeDays)
            )
        ),
        goal = ReadRecordGoalUi(
            userName = goalConfig.userName.orEmpty().ifBlank { "今日阅读" },
            progress = if (goalMs > 0L) todayTime.toFloat() / goalMs else 0f,
            currentText = context.formatDuration(todayTime),
            targetText = context.formatDuration(goalMs),
            helper = "累计 ${context.formatDuration(totalTime)} · 已读 $readBookCount 本"
        ),
        heatmap = heatmapCells,
        recentBooks = recentBooks,
        rankItems = rankItems,
        dailyRecords = dailyStats.take(14).map { it.toReadRecordDailyUi(context) },
        componentOrder = loadReadRecordComponentOrder(),
        emptyHint = "暂无阅读记录"
    )
}

private fun List<ReadRecordDaily>.toDailySummaries(): List<DailyReadSummary> {
    return mapNotNull { record ->
        runCatching {
            DailyReadSummary(LocalDate.parse(record.date), record.readTime)
        }.getOrNull()
    }.sortedByDescending { it.date }
}

private fun buildHeatmapCells(
    selectedDate: LocalDate,
    dailyMap: Map<LocalDate, Long>
): List<ReadRecordHeatmapCellUi> {
    val startDate = selectedDate.minusDays(111)
    val maxReadTime = max(dailyMap.values.maxOrNull() ?: 0L, 1L)
    return (0L..111L).map { offset ->
        val date = startDate.plusDays(offset)
        val readTime = dailyMap[date] ?: 0L
        ReadRecordHeatmapCellUi(
            key = date.toString(),
            level = readTime.toFloat() / maxReadTime,
            contentDescription = date.toString()
        )
    }
}

private fun buildRankItems(): List<ReadRecordRankUi> {
    val rankItems = ReadRecordWidgetStore.buildRankItems(10)
    val maxReadTime = max(rankItems.maxOfOrNull { it.readTime } ?: 0L, 1L)
    return rankItems.map { item ->
        ReadRecordRankUi(
            id = item.book?.bookUrl ?: item.displayName,
            title = item.book?.name ?: item.snapshot?.name ?: item.displayName,
            author = item.book?.author ?: item.snapshot?.author ?: item.displayAuthor,
            readTimeText = splitties.init.appCtx.formatDuration(item.readTime),
            percent = item.readTime.toFloat() / maxReadTime
        )
    }
}

private fun Book.toReadRecordBookUi(
    context: Context,
    readTime: Long
): ReadRecordBookUi {
    val lastOpenFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val chapterText = durChapterTitle
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: if (totalChapterNum > 0) {
            "第 ${durChapterIndex + 1}/${totalChapterNum} 章"
        } else {
            ""
        }
    return ReadRecordBookUi(
        id = bookUrl,
        title = name,
        author = author,
        progressText = chapterText,
        lastReadText = context.getString(
            R.string.read_record_last_open,
            lastOpenFormatter.format(Date(durChapterTime))
        ),
        readTimeText = context.formatDuration(readTime)
    )
}

private fun DailyReadSummary.toReadRecordDailyUi(context: Context): ReadRecordDailyUi {
    return ReadRecordDailyUi(
        id = date.toString(),
        dateText = date.format(DateTimeFormatter.ofPattern(
            context.getString(R.string.read_record_date_pattern),
            Locale.getDefault()
        )),
        readTimeText = context.formatDuration(readTime),
        bookCountText = when (date) {
            LocalDate.now() -> context.getString(R.string.read_record_today_word)
            LocalDate.now().minusDays(1) -> context.getString(R.string.read_record_yesterday_word)
            else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        }
    )
}

private fun loadReadRecordComponentOrder(): List<ReadRecordComponentUi> {
    val order = ReadRecordComponents.load()
        .filter { it.enabled }
        .mapNotNull { item ->
            when (item.type) {
                ReadRecordComponentType.GOAL_CARD -> ReadRecordComponentUi.Goal
                ReadRecordComponentType.OVERVIEW -> ReadRecordComponentUi.Overview
                ReadRecordComponentType.HEATMAP -> ReadRecordComponentUi.Heatmap
                ReadRecordComponentType.RECENT_BOOKS,
                ReadRecordComponentType.RECENT_COVERS -> ReadRecordComponentUi.RecentBooks
                ReadRecordComponentType.READ_RANK -> ReadRecordComponentUi.Ranking
                ReadRecordComponentType.DAILY_RECORDS -> ReadRecordComponentUi.DailyRecords
            }
        }
        .distinct()
    return order.ifEmpty { ReadRecordComponentUi.entries }
}

private fun Context.formatDuration(mss: Long): String {
    val days = mss / (1000 * 60 * 60 * 24)
    val hours = mss % (1000 * 60 * 60 * 24) / (1000 * 60 * 60)
    val minutes = mss % (1000 * 60 * 60) / (1000 * 60)
    val seconds = mss % (1000 * 60) / 1000
    val d = if (days > 0) getString(R.string.duration_day, days) else ""
    val h = if (hours > 0) getString(R.string.duration_hour, hours) else ""
    val m = if (minutes > 0) getString(R.string.duration_minute, minutes) else ""
    val s = if (seconds > 0 && days == 0L && hours == 0L) {
        getString(R.string.duration_second, seconds)
    } else {
        ""
    }
    val time = "$d$h$m$s"
    return if (time.isBlank()) getString(R.string.duration_zero) else time
}

private data class DailyReadSummary(
    val date: LocalDate,
    val readTime: Long
)
