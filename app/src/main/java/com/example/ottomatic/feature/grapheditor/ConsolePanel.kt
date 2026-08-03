package com.example.ottomatic.feature.grapheditor

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
 * puts the canvas back.
 */
@Composable
fun ConsoleBody(
    entries: StateFlow<List<LogEntry>>,
    minLevel: StateFlow<LogLevel>,
    onMinLevelChange: (LogLevel) -> Unit,
    onSelectNode: (NodeId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val all by entries.collectAsState()
    val level by minLevel.collectAsState()
    val visible = remember(all, level) { all.filter { it.level >= level } }

    Column(modifier = modifier.fillMaxSize()) {
        LevelFilter(selected = level, onSelect = onMinLevelChange)
        if (visible.isEmpty()) {
            EmptyConsole(filtered = all.isNotEmpty())
        } else {
            LogList(entries = visible, onSelectNode = onSelectNode)
        }
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
        FILTERS.forEach { (label, level) ->
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
                label = { Text(label, fontSize = 13.sp) },
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
 */
@Composable
private fun LogList(entries: List<LogEntry>, onSelectNode: (NodeId) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        items(entries.asReversed(), key = { it.id }) { entry ->
            LogRow(entry, onSelectNode)
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, onSelectNode: (NodeId) -> Unit) {
    val nodeId = entry.source?.nodeId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (nodeId == null) Modifier
                else Modifier.clickable { onSelectNode(NodeId(nodeId)) },
            )
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
                    text = formatLogTime(entry.atMs),
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
            )
        }
    }
}

@Composable
private fun EmptyConsole(filtered: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 36.dp),
        ) {
            Text(
                text = if (filtered) "Nothing at this level" else "Nothing logged yet",
                color = EditorColors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (filtered) {
                    "Widen the filter to see the rest of this workflow's history."
                } else {
                    "Run the workflow. Errors, action.log messages and a script's console.log appear here."
                },
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.DEBUG -> EditorColors.textSecondary
    LogLevel.INFO -> EditorColors.actionAccent
    LogLevel.WARN -> EditorColors.warnAccent
    LogLevel.ERROR -> EditorColors.triggerAccent
}

private val FILTERS = listOf(
    "All" to LogLevel.DEBUG,
    "Info" to LogLevel.INFO,
    "Problems" to LogLevel.WARN,
)

/** Immutable and thread-safe, so one instance is fine. */
private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/**
 * The millis are kept deliberately: within one run, the question being asked is
 * almost always "which of these two happened first".
 */
internal fun formatLogTime(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    LOG_TIME.format(Instant.ofEpochMilli(atMs).atZone(zone))
