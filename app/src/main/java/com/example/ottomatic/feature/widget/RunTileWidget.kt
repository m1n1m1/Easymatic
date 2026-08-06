package com.example.ottomatic.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.example.ottomatic.core.service.RunFeedback

/**
 * One manual trigger, as a button on the home screen.
 *
 * The smallest of the three and the one people will actually place: a macro they
 * run by hand several times a day earns its own icon next to their apps, and a
 * deck is overkill for it.
 *
 * Three [SizeMode.Responsive] buckets rather than a continuous measure, because
 * there are only three genuinely different things to say at these sizes and
 * Responsive builds them all once instead of recomposing on every drag of the
 * resize handle:
 *
 *  - **1×1** — a smaller chip with the label stacked under it. There is no room for
 *    a word *beside* the chip, which is not the same as no room for one at all: a
 *    tile nobody can name is a tile you have to remember, and the icon identifies a
 *    macro only until the second one is placed next to it.
 *  - **2×1** — chip and label, which is the shape a launcher icon already has.
 *  - **2×2 and up** — chip, label and the state line, which is the only size with
 *    room to say "Failed" as well as show it.
 */
class RunTileWidget : GlanceAppWidget() {

    override val stateDefinition = androidx.glance.state.PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(
        setOf(
            SMALLEST_SIZE,
            DpSize(130.dp, 57.dp),
            DpSize(130.dp, 110.dp),
        ),
    )

    /**
     * Reads the trigger **before** composing, and keeps the feedback map as a plain
     * snapshot rather than collecting the flow.
     *
     * A Glance session is torn down when the widget is not being interacted with,
     * so a `collectAsState` here would keep a tile up to date for exactly as long
     * as somebody was already looking at it. Redraws are pushed instead, by
     * `WidgetUpdater`, which is the only mechanism that works while the screen is
     * off — and a widget's whole job is to be right before anybody looks.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val triggers = MacroSnapshots.triggers()
        val feedback = RunFeedback.entries.value
        val now = System.currentTimeMillis()
        provideContent {
            val prefs = currentState<Preferences>()
            val trigger = prefs[RUN_TILE_TRIGGER_KEY]?.let { key ->
                triggers.firstOrNull { it.key == key }
            }
            OttomaticWidgetTheme {
                if (trigger == null) {
                    MissingTrigger()
                } else {
                    val size = LocalSize.current
                    val state = RunFeedback.displayState(feedback[trigger.key], now)
                    // The card is drawn once here rather than inside each
                    // arrangement, because it belongs to *this widget* — a deck
                    // cell draws no card at all, since the deck's own card is
                    // already underneath it.
                    val card = GlanceModifier.fillMaxSize().tileCard()
                    when {
                        size.width < NARROW_WIDTH -> MacroTileNarrow(trigger, state, card)
                        size.height < SHORT_HEIGHT ->
                            MacroTileWide(trigger, state, showState = false, modifier = card)

                        else -> MacroTileWide(trigger, state, showState = true, modifier = card)
                    }
                }
            }
        }
    }

    /**
     * The tile whose macro is gone — deleted, or its manual trigger removed.
     *
     * Says so rather than rendering an empty card, and deliberately does **not**
     * disappear: the launcher owns whether a widget is on the screen, and a tile
     * that silently blanked itself would look like the app had broken rather than
     * like a macro had been deleted. Tapping opens the app, which is where it can
     * be pointed at something again.
     */
    @Composable
    private fun MissingTrigger() {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .widgetSurface()
                .clickableToOpenApp()
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Macro not found",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

/**
 * The 1×1 bucket: 70n−30 for one cell, the floor a launcher may hand this widget.
 *
 * Named because [MacroTileNarrow] has to fit inside it — a responsive bucket is the
 * size the content is designed against, and the whole point of the arrangement is
 * that its label is *not* clipped at the smallest size it can be placed at.
 */
internal val SMALLEST_SIZE = DpSize(57.dp, 57.dp)

/** Narrower than this and there is no room for a word beside the chip. */
private val NARROW_WIDTH = 110.dp

/** Shorter than this and a second line of text would clip rather than fit. */
private val SHORT_HEIGHT = 92.dp

/** Manifest entry point for [RunTileWidget]. */
class RunTileReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RunTileWidget()
}

/**
 * Points an already-placed Run tile at [triggerKey].
 *
 * Called by the config activity, and separate from it so a future "reconfigure"
 * path does not have to go through an Activity to change one string.
 */
suspend fun setRunTileTrigger(context: Context, glanceId: GlanceId, triggerKey: String) {
    updateAppWidgetState(context, glanceId) { prefs ->
        prefs[RUN_TILE_TRIGGER_KEY] = triggerKey
    }
    RunTileWidget().update(context, glanceId)
}
