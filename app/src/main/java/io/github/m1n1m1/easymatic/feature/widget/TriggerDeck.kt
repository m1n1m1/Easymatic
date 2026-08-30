package io.github.m1n1m1.easymatic.feature.widget

import androidx.glance.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import io.github.m1n1m1.easymatic.core.service.RunFeedback

/**
 * A grid of manual triggers — the deck widget's whole body, and the lower half of
 * the status widget when it is tall enough to have one.
 *
 * ## Why a hand-laid grid rather than `LazyVerticalGrid`
 *
 * Glance does have one, and it would be the obvious choice for a long list. This
 * is not a long list: a deck shows what fits, and what fits is decided by the
 * widget's measured size, which [columnsFor] and [rowsFor] already compute. Rows of
 * `Row`s cost one `RemoteViews` tree with no adapter behind it, where a lazy grid
 * brings a `RemoteViewsService` and its own process hop for a dozen cells that were
 * never going to scroll. It also keeps the cells' widths genuinely equal —
 * `defaultWeight()` divides the row — which a lazy grid's fixed cell sizing does
 * not, and unequal cells are immediately visible in a grid of coloured chips.
 *
 * Anything past the budget is **not** silently dropped: [DeckOverflow] says how
 * many did not fit, because a deck that quietly shows eight of your twelve buttons
 * is a deck you cannot trust.
 */
@Composable
fun TriggerDeck(
    triggers: List<ManualTriggerRef>,
    feedback: Map<String, RunFeedback.Entry>,
    nowMs: Long,
    columns: Int,
    rows: Int,
    modifier: GlanceModifier = GlanceModifier,
) {
    val budget = columns * rows
    val shown = triggers.take(budget)
    val hidden = triggers.size - shown.size

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        shown.chunked(columns).forEachIndexed { rowIndex, rowItems ->
            if (rowIndex > 0) Spacer(GlanceModifier.height(ROW_GAP))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                rowItems.forEachIndexed { cellIndex, trigger ->
                    if (cellIndex > 0) Spacer(GlanceModifier.width(CELL_GAP))
                    MacroTileCompact(
                        trigger = trigger,
                        state = RunFeedback.displayState(feedback[trigger.key], nowMs),
                        modifier = GlanceModifier.defaultWeight().height(CELL_HEIGHT),
                    )
                }
                // The last row is usually short. Without these the surviving cells
                // stretch to fill it and the grid stops looking like a grid — the
                // three-cell row would centre its chips under nothing.
                repeat(columns - rowItems.size) {
                    Spacer(GlanceModifier.width(CELL_GAP))
                    Box(modifier = GlanceModifier.defaultWeight()) {}
                }
            }
        }
        if (hidden > 0) {
            Spacer(GlanceModifier.height(ROW_GAP))
            DeckOverflow(hidden)
        }
    }
}

/** What is missing, said out loud rather than left to be noticed. */
@Composable
private fun DeckOverflow(hidden: Int) {
    Text(
        text = LocalContext.current.getString(R.string.widget_more_hidden, hidden) + if (hidden == 1) "it" else "them",
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        ),
        modifier = GlanceModifier.fillMaxWidth(),
    )
}

/** The empty state, which is a pointer rather than an apology. */
@Composable
fun DeckEmpty(modifier: GlanceModifier = GlanceModifier) {
    Box(
        modifier = modifier.padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.widget_no_manual_triggers_yet_nadd),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * How many cells fit across [width].
 *
 * Clamped at both ends: below two columns a "grid" is a list and the labels would
 * be the only thing on screen, and above six the chips are further apart than they
 * are wide, which stops reading as a group.
 */
fun columnsFor(width: Dp): Int =
    (width.value / CELL_TARGET_WIDTH.value).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)

/**
 * How many rows fit in [height].
 *
 * Never fewer than one — a deck that computed zero rows would render nothing and
 * look broken — and the gap is counted *between* rows rather than after each, so a
 * height of exactly two cells plus one gap fits two rows instead of one.
 */
fun rowsFor(height: Dp): Int {
    val perRow = CELL_HEIGHT.value + ROW_GAP.value
    return ((height.value + ROW_GAP.value) / perRow).toInt().coerceAtLeast(1)
}

/** Below this a "grid" is a list, and the labels are the only thing on screen. */
private const val MIN_COLUMNS = 2

/** Above this the chips are further apart than they are wide and stop reading as a group. */
private const val MAX_COLUMNS = 6

private val CELL_GAP = 4.dp
private val ROW_GAP = 12.dp

/**
 * The width a cell wants before another column is worth having.
 *
 * Set by the label, not the chip: 44dp of chip fits in far less, but a one-line
 * 12sp label needs about this much before it ellipsises after two syllables.
 */
private val CELL_TARGET_WIDTH = 76.dp
