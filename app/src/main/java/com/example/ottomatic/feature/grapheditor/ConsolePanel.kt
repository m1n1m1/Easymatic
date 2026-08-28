package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What one workflow's runs had to say.
 *
 * The debugging surface for `action.script` above all — a script's
 * `console.log` arrives here, and until it did there was nowhere in the app to
 * see why a script produced the wrong number.
 *
 * **It takes the flows rather than the lists, and collects them itself.** That
 * is not a style choice: `collectAsState` attributes the read to the composable
 * that calls it, so collecting one level up would invalidate `GraphEditorContent`
 * on every log line and re-run `GraphCanvas` — which will not skip, because a
 * ViewModel is not a stable type to Compose. The whole graph would repaint per
 * line. Collecting here keeps the invalidation inside this body, wherever it is
 * mounted.
 *
 * It fills the region above [EditorBottomBar], in place of the canvas, under the
 * [PanelTopBar] that carries its title and its Clear button.
 *
 * [onSelectNode] is a parameter rather than baked in because picking a line is a
 * request to go and look at the node it names — so the host both selects it and
 * puts the canvas back. That request is made from [LogEntryOverlay] rather than
 * from the row: a tap on a row now opens the line, which is the thing every row
 * can do, and "go to the node" becomes a labelled button instead of an invisible
 * property of some rows and not others. [onDeleteEntry] arrives there for the
 * same reason, and gains one of its own: a destructive action wants a label and a
 * deliberate tap, not an icon on a row that the finger reaching to read it lands
 * on.
 *
 * **Being on screen is what acknowledges the log.** The badge on the console tab
 * exists to say something went wrong that nobody has looked at; this body *is*
 * somebody looking, so it says so on every change to the list rather than once on
 * open — a line arriving mid-run while the console is watched has been seen too,
 * and re-badging the tab the user is standing on would be the same nag in a new
 * place.
 */
@Composable
fun ConsoleBody(
    entries: StateFlow<List<LogEntry>>,
    minLevel: StateFlow<LogLevel>,
    onMinLevelChange: (LogLevel) -> Unit,
    onSelectNode: (NodeId) -> Unit,
    onAcknowledge: () -> Unit,
    onDeleteEntry: (LogEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val all by entries.collectAsState()
    val level by minLevel.collectAsState()
    val visible = remember(all, level) { all.filter { it.level >= level } }
    var opened by remember { mutableStateOf<LogEntry?>(null) }

    LaunchedEffect(all) { onAcknowledge() }

    Column(modifier = modifier.fillMaxSize()) {
        LevelFilter(selected = level, onSelect = onMinLevelChange)
        if (visible.isEmpty()) {
            EmptyConsole(filtered = all.isNotEmpty())
        } else {
            LogList(entries = visible, onOpen = { opened = it })
        }
    }

    // A Dialog, so it takes no room in the column it is declared in.
    opened?.let { entry ->
        LogEntryOverlay(
            entry = entry,
            onClose = { opened = null },
            onShowNode = { nodeId ->
                opened = null
                onSelectNode(nodeId)
            },
            onDelete = {
                opened = null
                onDeleteEntry(entry)
            },
        )
    }
}

/**
 * The console's badge: how many lines are worth looking at.
 *
 * Its own composable so it can collect [problems] itself — a count that ticks up
 * during a run would otherwise invalidate whatever it is drawn inside, and through
 * that everything composed alongside.
 */
@Composable
fun ConsoleBadge(problems: StateFlow<Int>, content: @Composable () -> Unit) {
    val count by problems.collectAsState()
    BadgedBox(
        badge = {
            if (count > 0) {
                Badge(containerColor = EditorColors.triggerAccent) { Text("$count") }
            }
        },
        content = { content() },
    )
}

/**
 * Three chips rather than a checkbox per level: the useful question is only ever
 * "everything", "what the macro meant to say", or "what went wrong".
 *
 * The store keeps [LogLevel.DEBUG] whatever is selected here, so widening the
 * filter reveals history instead of demanding another run.
 */
@Composable
private fun LevelFilter(selected: LogLevel, onSelect: (LogLevel) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FILTERS.forEach { (labelRes, level) ->
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
                label = { Text(stringResource(labelRes), fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = EditorColors.textSecondary,
                    selectedContainerColor = EditorColors.actionAccent.copy(alpha = 0.22f),
                    selectedLabelColor = EditorColors.textPrimary,
                ),
            )
        }
    }
}

/**
 * Oldest at the top, newest pinned to the bottom — a terminal, with no scroll
 * effect anywhere.
 *
 * `reverseLayout` anchors index 0 to the bottom of the viewport, so feeding it
 * the list newest-first both pins new lines and leaves the view alone when the
 * user has scrolled up to read something. A `LaunchedEffect(size) { scrollTo }`
 * gets that second half wrong: it yanks the list away mid-read, and correcting
 * for it means tracking "is the user at the bottom", which is exactly the state
 * `reverseLayout` already encodes.
 *
 * [consoleRows] does the reversing now, because a day separator has to be placed
 * relative to the *rendered* order rather than the logged one.
 */
@Composable
private fun LogList(entries: List<LogEntry>, onOpen: (LogEntry) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val locale = LocalConfiguration.current.locales[0]
    val rows = remember(entries, zone) { consoleRows(entries, zone) }
    // One reading of "today" for the whole list, so two separators cannot disagree
    // about it if the list happens to recompose across midnight.
    val today = remember(rows) { LocalDate.now(zone) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        items(rows, key = ConsoleRow::key) { row ->
            when (row) {
                is ConsoleRow.Line -> LogRow(row.entry, zone, locale, onOpen)
                is ConsoleRow.Day -> DaySeparator(row.date, today)
            }
        }
    }
}

/**
 * A row is a summary, not the line itself.
 *
 * It is clamped to [ROW_MAX_LINES] because the store's bound is two thousand
 * characters, and one `action.log` of an HTTP body used to push every other line
 * off the screen — a log you have to scroll past to reach the next entry has
 * stopped being a list. The ellipsis is the affordance: tapping opens
 * [LogEntryOverlay], where the whole thing is readable and selectable.
 *
 * [zone] and [locale] come down from the list rather than being read here, so a
 * row's date and the separator above it cannot be resolved against different ones.
 */
@Composable
private fun LogRow(entry: LogEntry, zone: ZoneId, locale: Locale, onOpen: (LogEntry) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(entry) }
            .padding(vertical = 5.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(levelColor(entry.level)),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Row {
                Text(
                    text = formatLogRowStamp(entry.atMs, zone, locale),
                    color = EditorColors.textSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                entry.source?.nodeName?.let { name ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = name,
                        color = EditorColors.textSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        // The stamp is fixed-width and comes first, so the name is
                        // what has to give on a narrow screen — clipped rather than
                        // wrapped, which would put a second line above every message.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = entry.message,
                color = if (entry.level >= LogLevel.WARN) levelColor(entry.level) else EditorColors.textPrimary,
                fontSize = 12.sp,
                // The first monospace in the app, and it earns its place: the
                // timestamps line up, and most of what lands here is JSON or
                // script output that is unreadable proportionally spaced.
                fontFamily = FontFamily.Monospace,
                maxLines = ROW_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Which day the lines above it happened on.
 *
 * Today and yesterday are words rather than dates because that is how the two days
 * anybody is actually debugging get referred to — and a date the reader has to
 * compare against the calendar to place is a date they have to think about.
 * Everything older is a real date, written the way the current locale writes one.
 */
@Composable
private fun DaySeparator(date: LocalDate, today: LocalDate) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dayLabel(date, today),
            color = EditorColors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(modifier = Modifier.weight(1f), color = EditorColors.chromeBorder)
    }
}

@Composable
private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.console_today)
    today.minusDays(1) -> stringResource(R.string.console_yesterday)
    else -> formatLogDate(date, LocalConfiguration.current.locales[0])
}

@Composable
private fun EmptyConsole(filtered: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 36.dp),
        ) {
            Text(
                text = stringResource(
                    if (filtered) R.string.console_nothing_at_level else R.string.console_nothing_logged,
                ),
                color = EditorColors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (filtered) R.string.console_widen_filter else R.string.console_run_the_workflow,
                ),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

internal fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.DEBUG -> EditorColors.textSecondary
    LogLevel.INFO -> EditorColors.actionAccent
    LogLevel.WARN -> EditorColors.warnAccent
    LogLevel.ERROR -> EditorColors.triggerAccent
}

/**
 * The level's own name, for [LogEntryOverlay]. The list says it in colour alone,
 * which is enough while the four are side by side and nothing at all on its own.
 */
@StringRes
internal fun levelLabelRes(level: LogLevel): Int = when (level) {
    LogLevel.DEBUG -> R.string.console_level_debug
    LogLevel.INFO -> R.string.console_level_info
    LogLevel.WARN -> R.string.console_level_warn
    LogLevel.ERROR -> R.string.console_level_error
}

private val FILTERS = listOf(
    R.string.console_filter_all to LogLevel.DEBUG,
    R.string.console_filter_info to LogLevel.INFO,
    R.string.console_filter_problems to LogLevel.WARN,
)

/**
 * How much of a line a row shows before the overlay has to.
 *
 * Three is what keeps a short message whole — one line of text plus a wrap — while
 * a two-thousand-character one costs the same height as its neighbours.
 */
private const val ROW_MAX_LINES = 3
