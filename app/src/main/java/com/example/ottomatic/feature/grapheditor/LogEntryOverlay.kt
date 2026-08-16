package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.R
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.LogSource
import kotlinx.coroutines.delay
import java.time.ZoneId

/** How long the copy button stays a tick before turning back into a clipboard. */
private const val COPIED_MS = 1_600L

/**
 * One log line, whole.
 *
 * The console's rows are clamped to a few lines so the list stays a list — which
 * means the one place a long entry can actually be *read* is here. An HTTP body,
 * a stack trace or a script's JSON dump is exactly the thing somebody opens the
 * console for, and exactly the thing that does not fit on a row.
 *
 * It is an [EditorOverlay] rather than a third level of the console surface for
 * the reason the palette and the config form are: this is long, scrollable and
 * arrives from the bottom, and a full-screen window gets back-dismissal and a
 * scroll with no competing drag for free.
 *
 * **The message is selectable**, which is the point of the overlay as much as the
 * reading is — text selection is how a phone copies half a line, and a
 * `SelectionContainer` cannot live in the list itself without eating the taps that
 * open this. The copy button beside it takes the whole message in one press, which
 * is the commoner want and the one selection handles are worst at.
 *
 * [onShowNode] is routed through a pending slot rather than called directly,
 * because the caller tears this overlay down the moment the canvas comes back:
 * fired mid-animation, the panel would vanish instead of dropping away. Setting it
 * and calling `dismiss` plays the exit first, and [EditorOverlay] then reports the
 * close — see the `onClose` below, which is where the navigation actually happens.
 */
@Composable
fun LogEntryOverlay(
    entry: LogEntry,
    onClose: () -> Unit,
    onShowNode: (NodeId) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var pendingNode by remember { mutableStateOf<NodeId?>(null) }

    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(COPIED_MS)
        copied = false
    }

    EditorOverlay(
        title = stringResource(R.string.console_entry_title),
        onClose = { pendingNode?.let(onShowNode) ?: onClose() },
        action = {
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(entry.message))
                    copied = true
                },
            ) {
                // Android 13 and up shows its own clipboard confirmation; everything
                // below it shows nothing at all, so the button says so itself.
                Icon(
                    imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.console_copy_message),
                    tint = if (copied) EditorColors.actionAccent else EditorColors.textPrimary,
                )
            }
        },
    ) { dismiss ->
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            EntryMeta(entry)
            Spacer(Modifier.height(16.dp))
            SelectionContainer {
                Text(
                    text = entry.message,
                    color = if (entry.level >= LogLevel.WARN) {
                        levelColor(entry.level)
                    } else {
                        EditorColors.textPrimary
                    },
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            entry.source?.nodeId?.let { nodeId ->
                Spacer(Modifier.height(24.dp))
                TextButton(
                    onClick = {
                        pendingNode = NodeId(nodeId)
                        dismiss()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.MyLocation,
                        contentDescription = null,
                        tint = EditorColors.actionAccent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.console_show_node),
                        color = EditorColors.actionAccent,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

/**
 * Everything about the line that is not the line: how loud it was, when, from
 * where, and which run.
 *
 * The date is spelled out here even when the list is showing "Today" a few rows
 * up, because this surface is also what gets read from a bug report — and a
 * timestamp with no date is the thing that made the day worth adding in the first
 * place.
 */
@Composable
private fun EntryMeta(entry: LogEntry) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember { ZoneId.systemDefault() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(levelColor(entry.level)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(levelLabelRes(entry.level)),
            color = levelColor(entry.level),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = formatLogStamp(entry.atMs, zone, locale),
        color = EditorColors.textSecondary,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
    )
    val source = entry.source
    if (source?.nodeName != null || source?.runId?.takeIf { it != LogSource.NO_RUN } != null) {
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            source?.nodeName?.let { name ->
                Text(
                    text = name,
                    color = EditorColors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            source?.runId?.takeIf { it != LogSource.NO_RUN }?.let { runId ->
                Text(
                    text = stringResource(R.string.console_run_number, runId),
                    color = EditorColors.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
