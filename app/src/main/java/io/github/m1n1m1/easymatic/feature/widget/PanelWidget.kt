package io.github.m1n1m1.easymatic.feature.widget

import androidx.glance.LocalContext
import androidx.compose.ui.res.stringResource
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.core.service.RunFeedback
import io.github.m1n1m1.easymatic.engine.service.MacroEngineService

/**
 * The whole of Easymatic on the home screen: engine status, problems, the last
 * run, and a grid of manual triggers — each section switched on or off per widget.
 *
 * ## Why one widget and not two
 *
 * This replaces a separate "Trigger deck" and "Engine status". They were split
 * because they answer different questions, but the split cost more than it
 * explained. The status widget already grew a deck when it was tall enough, so
 * two of the three sizes of one were the other; a user who wanted status *and*
 * buttons had to place two cards and align them by hand; and every option belonged
 * to whichever one happened to own it, so "show the header" existed on the deck
 * and nowhere else.
 *
 * A panel with switches says the same thing without the taxonomy. Turn everything
 * off but the triggers and it is the old deck. Turn the triggers off and it is the
 * old status widget. Both were configurations of one thing all along.
 *
 * ## Everything is optional, and it can be empty
 *
 * A panel with every section off is legal and says so ([EmptyPanel]) rather than
 * being prevented. Guarding against it would mean a config screen that refuses to
 * save, and there is a legitimate reading — someone clearing it out before
 * choosing what to put back.
 *
 * [SizeMode.Exact] rather than a few responsive buckets: the column count is
 * genuinely continuous here, since a panel is resized to fit a gap on a home
 * screen, and rounding four-and-a-bit columns down to a two-column bucket wastes a
 * third of the space it was given.
 */
class PanelWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val macros = MacroSnapshots.all()
        val allTriggers = macros.flatMap { it.triggers }
        val feedback = RunFeedback.entries.value
        val lastRun = RunFeedback.lastRun.value
        val running = MacroEngineService.engineRunning.value
        val armed = MacroEngineService.armedCount.value
        val problems = macros.sumOf { it.errorCount }
        val now = System.currentTimeMillis()

        provideContent {
            val config = PanelConfig.from(currentState())
            EasymaticWidgetTheme {
                Panel(
                    config = config,
                    triggers = config.selectTriggers(allTriggers),
                    feedback = feedback,
                    lastRun = lastRun,
                    running = running,
                    armed = armed,
                    total = macros.size,
                    problems = problems,
                    now = now,
                )
            }
        }
    }

    @Composable
    @Suppress("LongParameterList") // One widget's worth of independently-toggled sections.
    private fun Panel(
        config: PanelConfig,
        triggers: List<ManualTriggerRef>,
        feedback: Map<String, RunFeedback.Entry>,
        lastRun: RunFeedback.Entry?,
        running: Boolean,
        armed: Int,
        total: Int,
        problems: Int,
        now: Long,
    ) {
        val size = LocalSize.current
        val showProblems = config.showProblems && problems > 0
        val showLastRun = config.showLastRun && lastRun != null
        val headerHeight = headerHeight(config.showStatus, showProblems, showLastRun)

        Column(modifier = GlanceModifier.fillMaxSize().widgetSurface().padding(PANEL_PADDING)) {
            if (config.isEmpty) {
                EmptyPanel(GlanceModifier.fillMaxSize())
                return@Column
            }
            if (config.showStatus) StatusRow(running, armed, total)
            if (showProblems) {
                if (config.showStatus) Spacer(GlanceModifier.height(SECTION_GAP))
                ProblemPill(problems)
            }
            if (showLastRun && lastRun != null) {
                Spacer(GlanceModifier.height(SECTION_GAP))
                Text(
                    text = lastRunLine(LocalContext.current, lastRun, now),
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                )
            }
            if (!config.showTriggers) return@Column

            // The grid takes whatever the header left and centres itself in it. A
            // panel is resized to fit a gap on a home screen, so it is routinely
            // taller than its contents — three triggers pinned under the header
            // left two thirds of the card visibly empty, which read as a layout
            // that had failed rather than as a widget with room to spare.
            val deckHeight = size.height - headerHeight - PANEL_PADDING * 2
            Box(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    triggers.isEmpty() -> DeckEmpty(GlanceModifier.fillMaxWidth())
                    // Below one whole cell there is no honest way to draw a grid;
                    // a row clipped in half looks like a rendering bug rather than
                    // like a size the user chose.
                    deckHeight < CELL_HEIGHT -> TooShort()
                    else -> TriggerDeck(
                        triggers = triggers,
                        feedback = feedback,
                        nowMs = now,
                        columns = columnsFor(size.width - PANEL_PADDING * 2),
                        rows = rowsFor(deckHeight),
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    /** Engine state and how much of the library it is holding. */
    @Composable
    private fun StatusRow(running: Boolean, armed: Int, total: Int) {
        Row(
            // fillMaxWidth() is load-bearing, not decoration. Without it the Row
            // wraps its content, `defaultWeight()` on the title has nothing to
            // expand into, and the count lands hard against the last letter of the
            // title — which is exactly how this read: "Engine running5 · 1 armed".
            modifier = GlanceModifier.fillMaxWidth().clickableToOpenApp(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_dot),
                contentDescription = null,
                colorFilter = ColorFilter.tint(
                    if (running) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                ),
                modifier = GlanceModifier.size(10.dp),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = LocalContext.current.getString(
                    if (running) R.string.widget_engine_running else R.string.widget_engine_stopped,
                ),
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(8.dp))
            // "1 of 5 armed", not "5 · 1 armed". Two bare numbers separated by a dot
            // make the reader work out which is which, and the first one read as
            // part of the title beside it.
            Pill(
                text = LocalContext.current.getString(R.string.widget_armed_of_total, armed, total),
                container = GlanceTheme.colors.secondaryContainer,
                onContainer = GlanceTheme.colors.onSecondaryContainer,
            )
        }
    }

    /**
     * The problem count, drawn only when there is one.
     *
     * A filled `errorContainer` pill rather than red text on the surface: at 12sp,
     * `error` on `surface` is the one Material pairing reliably too low-contrast to
     * read at a glance, which is the only way this line is ever read.
     */
    @Composable
    private fun ProblemPill(problems: Int) {
        Row(
            modifier = GlanceModifier
                .background(
                    ImageProvider(R.drawable.widget_pill),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.errorContainer),
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clickableToOpenApp(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_warning),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onErrorContainer),
                modifier = GlanceModifier.size(14.dp),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = LocalContext.current.resources
                    .getQuantityString(R.plurals.widget_problem_count, problems, problems),
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onErrorContainer,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }

    @Composable
    private fun Pill(text: String, container: ColorProvider, onContainer: ColorProvider) {
        Box(
            modifier = GlanceModifier
                .background(ImageProvider(R.drawable.widget_pill), colorFilter = ColorFilter.tint(container))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(
                text = text,
                maxLines = 1,
                style = TextStyle(color = onContainer, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            )
        }
    }

    @Composable
    private fun EmptyPanel(modifier: GlanceModifier) {
        Box(modifier = modifier.clickableToOpenApp(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.widget_nothing_to_show_nlong_press),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = androidx.glance.text.TextAlign.Center,
                ),
            )
        }
    }

    @Composable
    private fun TooShort() {
        Text(
            text = stringResource(R.string.widget_make_this_taller_to_see),
            maxLines = 2,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp,
                textAlign = androidx.glance.text.TextAlign.Center,
            ),
        )
    }

    /**
     * How tall everything above the grid will be, so the grid knows what is left.
     *
     * Computed rather than measured because Glance has no measurement pass to ask —
     * a widget's layout is decided when the `RemoteViews` tree is built, not after
     * it is laid out. The numbers are the font sizes plus the spacer above each;
     * they only decide whether another whole cell row fits, so a few dp either way
     * costs nothing.
     */
    private fun headerHeight(status: Boolean, problems: Boolean, lastRun: Boolean): Dp {
        var height = 0.dp
        if (status) height += STATUS_HEIGHT
        if (problems) height += SECTION_GAP + PILL_HEIGHT
        if (lastRun) height += SECTION_GAP + LINE_HEIGHT
        // The grid gets its own breathing room below whatever the header came to,
        // but only when there is a header to breathe away from.
        if (height > 0.dp) height += SECTION_GAP
        return height
    }
}

/** Manifest entry point for [PanelWidget]. */
class PanelReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PanelWidget()
}

/**
 * Auto mode takes every trigger; picked mode takes the chosen keys **in the order
 * they were chosen**, which is the whole point of picking.
 *
 * Keys that no longer resolve are dropped rather than shown as gaps: a panel is a
 * set of buttons, and a hole where a deleted macro used to be is not a button. The
 * Run tile does the opposite and says "Macro not found", because there the missing
 * macro is the entire widget — here it is one cell among a dozen that still work.
 */
internal fun PanelConfig.selectTriggers(all: List<ManualTriggerRef>): List<ManualTriggerRef> {
    if (triggersAuto) return all
    val byKey = all.associateBy { it.key }
    return triggerKeys.mapNotNull { byKey[it] }
}

/**
 * "Morning Routine · ok · 4m ago".
 *
 * Relative rather than a clock time, because the question a glance asks is "was
 * that recent?" and not "at what o'clock?" — and because a widget that has not been
 * redrawn since yesterday would otherwise show a plausible-looking time for a run
 * that is a day old. Anything past a week says so instead of counting.
 */
internal fun lastRunLine(context: Context, entry: RunFeedback.Entry, nowMs: Long): String {
    val outcome = context.getString(
        when (entry.state) {
            RunFeedback.State.RUNNING -> R.string.widget_outcome_running
            RunFeedback.State.DONE -> R.string.widget_outcome_ok
            RunFeedback.State.FAILED -> R.string.widget_outcome_failed
        },
    )
    return context.getString(
        R.string.widget_last_run_line,
        entry.macroName,
        outcome,
        relativeTime(context, nowMs - entry.atMs),
    )
}

internal fun relativeTime(context: Context, agoMs: Long): String {
    val minutes = agoMs / MS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        minutes < 1 -> context.getString(R.string.widget_ago_just_now)
        minutes < MINUTES_PER_HOUR -> context.getString(R.string.widget_ago_minutes, minutes)
        hours < HOURS_PER_DAY -> context.getString(R.string.widget_ago_hours, hours)
        days < DAYS_SHOWN -> context.getString(R.string.widget_ago_days, days)
        else -> context.getString(R.string.widget_ago_a_while)
    }
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L

/** Past a week, the exact number stops being the point. */
private const val DAYS_SHOWN = 7L

/** Material You's widget padding. 8dp made the cells look glued to the card's rim. */
private val PANEL_PADDING = 14.dp
private val SECTION_GAP = 8.dp

// What each header section costs, including nothing above it; see headerHeight.
private val STATUS_HEIGHT = 24.dp
private val PILL_HEIGHT = 26.dp
private val LINE_HEIGHT = 18.dp
