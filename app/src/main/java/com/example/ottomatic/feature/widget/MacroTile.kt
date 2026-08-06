package com.example.ottomatic.feature.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.ottomatic.R
import com.example.ottomatic.core.service.RunFeedback
import com.example.ottomatic.feature.macro.drawableRes

/**
 * The visual unit every one of the three widgets is made of.
 *
 * There is one of these rather than one per widget because the three genuinely
 * show the same object: a button for a manual trigger. A deck cell and a
 * standalone Run tile differ in *layout* — stacked versus side by side — and in
 * nothing else, so they are two arrangements here and not two components.
 *
 * ## The filled container is the whole design
 *
 * Material You's vocabulary for "a thing you press" is a **tonal container**: an
 * opaque, generously rounded fill in a light tone with dark content on it, and the
 * reverse at night. So the accent is a container/on-container *pair*
 * ([MacroAccent.widgetColors]), the chip is filled with the first and the glyph
 * drawn in the second.
 *
 * The first version tinted a 15%-alpha square with the accent itself and put the
 * glyph on top in the same colour. That is a wash, not a container: on a dark
 * widget background it read as a faint smudge, every accent looked like every
 * other one at a glance, and the result was a flat grey card — which is exactly
 * what it was.
 *
 * A **deck cell draws no card of its own.** The card underneath it is already
 * `widgetBackground`, so a second surface in the same colour is invisible and
 * costs 8dp of padding for nothing. The chip is the only filled thing, and the
 * cell around it is transparent and clickable — which is also why a deck now reads
 * as a row of launcher icons rather than as a grid of empty boxes.
 *
 * ## Corners
 *
 * Every surface here is a **shape drawable tinted through a `ColorFilter`**, never
 * `background(ColorProvider)`. Glance's `cornerRadius` modifier is a no-op below
 * API 31 and minSdk is 26, so the plain-colour route would draw square cards for a
 * third of the supported range. The drawables' own colours are placeholders the
 * tint always replaces; `drawable-v31/` restates the card shapes at the system
 * widget radius so Android 12+ matches whatever corner the launcher uses.
 *
 * ## Reporting a run
 *
 * A widget cannot animate, so the whole vocabulary is: the glyph is replaced and
 * the line under it changes. [RunFeedback.displayState] decides which, and decays a
 * finished run back to idle after a few seconds — without that a tile would still
 * be saying "Done" the next morning about a tap nobody remembers.
 */

/** The horizontal form: chip on the left, label and state stacked beside it. */
@Composable
fun MacroTileWide(
    trigger: ManualTriggerRef,
    state: RunFeedback.State?,
    showState: Boolean,
    modifier: GlanceModifier = GlanceModifier,
) {
    Row(
        modifier = modifier.runAction(trigger).padding(TILE_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip(trigger, state)
        Spacer(GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = trigger.label,
                maxLines = if (showState) 1 else 2,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            if (showState) {
                Text(
                    text = stateLine(trigger, state),
                    maxLines = 1,
                    style = TextStyle(color = stateColor(state), fontSize = 12.sp),
                )
            }
        }
    }
}

/**
 * The compact form a deck cell uses: chip above the label.
 *
 * The label is **one line**, and that is the fix for the clipping in the first
 * version rather than a compromise. Two lines needed 28dp of a cell that had 20dp
 * left after the chip, so every label lost its bottom half — and the labels that
 * would have wrapped are exactly the long ones, which are unreadable at 11sp in a
 * 76dp column anyway. One line that ellipsises says more than two lines with their
 * descenders cut off.
 */
@Composable
fun MacroTileCompact(
    trigger: ManualTriggerRef,
    state: RunFeedback.State?,
    modifier: GlanceModifier = GlanceModifier,
) {
    Column(
        modifier = modifier.runAction(trigger),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.Top,
    ) {
        Chip(trigger, state)
        Spacer(GlanceModifier.height(CHIP_LABEL_GAP))
        Text(
            text = trigger.label,
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * The narrowest standalone form: a smaller chip with the label under it.
 *
 * This was the chip alone, filling the tile, and the label is why that was wrong: a
 * 1×1 tile is the size people place *several* of, and a row of accent-coloured
 * squares is a row of things you have to remember rather than read. The macro's own
 * icon identifies it only for as long as every macro on the screen has a different
 * one, which stops being true at about the fourth.
 *
 * So the chip shrinks to [CHIP_SIZE_NARROW] to make room, and the whole stack is
 * **centred rather than padded to the top**. A 1×1 cell is the one size whose real
 * dimensions vary most between launchers — the 57dp bucket is a floor, and most
 * hand over half as much again — so centring is what puts the contents in the middle
 * of whatever actually arrives instead of leaving a gap under them.
 *
 * One line of label, for [MacroTileCompact]'s reason: two lines do not fit here at
 * all, and the labels that would wrap are the long ones, which are unreadable at
 * this width either way.
 */
@Composable
fun MacroTileNarrow(
    trigger: ManualTriggerRef,
    state: RunFeedback.State?,
    modifier: GlanceModifier = GlanceModifier,
) {
    Box(
        modifier = modifier.runAction(trigger).padding(horizontal = NARROW_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Chip(trigger, state, size = CHIP_SIZE_NARROW, glyph = GLYPH_SIZE_NARROW)
            Spacer(GlanceModifier.height(NARROW_LABEL_GAP))
            Text(
                text = trigger.label,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = NARROW_LABEL_SIZE,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

/**
 * The filled tonal square, and the glyph on it.
 *
 * While a run is reporting, the macro's own icon steps aside for the outcome — a
 * spinner glyph, a tick or a cross — and the *container* changes with it, so a
 * failure is a red chip rather than a red mark on a chip the same colour as
 * before. Drawing the outcome as a badge beside the icon was the alternative and
 * does not survive the sizes involved: a deck cell is about 76dp wide, and a badge
 * legible at that size is most of the chip anyway.
 */
@Composable
private fun Chip(
    trigger: ManualTriggerRef,
    state: RunFeedback.State?,
    size: androidx.compose.ui.unit.Dp = CHIP_SIZE,
    glyph: androidx.compose.ui.unit.Dp = GLYPH_SIZE,
) {
    val accent = trigger.accent.widgetColors()
    // `error`/`onError` rather than the container pair, to match the filled-button
    // treatment the accents use. A failure chip drawn as a calm tonal container
    // beside eight saturated accent chips would be the quietest thing on the
    // widget, which is the opposite of what it needs to be.
    val container = when (state) {
        RunFeedback.State.FAILED -> GlanceTheme.colors.error
        else -> accent.container
    }
    val onContainer = when (state) {
        RunFeedback.State.FAILED -> GlanceTheme.colors.onError
        else -> accent.onContainer
    }
    val drawable = when (state) {
        RunFeedback.State.RUNNING -> R.drawable.ic_widget_running
        RunFeedback.State.DONE -> R.drawable.ic_widget_check
        RunFeedback.State.FAILED -> R.drawable.ic_widget_close
        null -> trigger.icon.drawableRes()
    }
    Box(
        modifier = GlanceModifier
            .size(size)
            .background(ImageProvider(R.drawable.widget_chip), colorFilter = ColorFilter.tint(container)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(drawable),
            contentDescription = trigger.label,
            colorFilter = ColorFilter.tint(onContainer),
            modifier = GlanceModifier.size(glyph),
        )
    }
}

/**
 * The line under a tile's label.
 *
 * "Off" is deliberately not an error state and the tile stays tappable: the enabled
 * switch governs whether a macro listens for *background events*, and pressing its
 * button is not one of those. Saying so on the tile is what keeps a switched-off
 * macro running from being a surprise.
 */
private fun stateLine(trigger: ManualTriggerRef, state: RunFeedback.State?): String = when (state) {
    RunFeedback.State.RUNNING -> "Running…"
    RunFeedback.State.DONE -> "Done"
    RunFeedback.State.FAILED -> "Failed"
    null -> if (trigger.enabled) "Ready" else "Off"
}

@Composable
private fun stateColor(state: RunFeedback.State?): ColorProvider = when (state) {
    RunFeedback.State.FAILED -> GlanceTheme.colors.error
    RunFeedback.State.RUNNING, RunFeedback.State.DONE -> GlanceTheme.colors.primary
    null -> GlanceTheme.colors.onSurfaceVariant
}

/** Makes the whole tile the button, rather than only the chip inside it. */
private fun GlanceModifier.runAction(trigger: ManualTriggerRef): GlanceModifier = this.clickable(
    actionRunCallback<RunTriggerAction>(
        actionParametersOf(
            RunTriggerAction.WORKFLOW_ID_KEY to trigger.workflowId,
            RunTriggerAction.NODE_ID_KEY to trigger.nodeId,
            RunTriggerAction.LABEL_KEY to trigger.label,
            RunTriggerAction.MACRO_NAME_KEY to trigger.macroName,
        ),
    ),
)

/** The card a *standalone* tile draws. A deck cell has none; see [MacroTileCompact]. */
@Composable
fun GlanceModifier.tileCard(): GlanceModifier = this.background(
    ImageProvider(R.drawable.widget_tile),
    colorFilter = ColorFilter.tint(GlanceTheme.colors.widgetBackground),
)

internal val CHIP_SIZE = 44.dp
internal val CHIP_LABEL_GAP = 6.dp
private val GLYPH_SIZE = 24.dp
private val TILE_PADDING = 14.dp

/** [MacroTileNarrow]'s smaller chip, and the label that fits beside it because of it. */
internal val CHIP_SIZE_NARROW = 28.dp
private val GLYPH_SIZE_NARROW = 16.dp
internal val NARROW_LABEL_GAP = 3.dp
private val NARROW_LABEL_SIZE = 11.sp
private val NARROW_PADDING = 4.dp

/** A 12sp line, with the leading Glance's default text style adds around it. */
private val LABEL_LINE_HEIGHT = 18.dp

/** The same, for [MacroTileNarrow]'s 11sp. */
internal val NARROW_LABEL_LINE_HEIGHT = 16.dp

/**
 * How tall one deck cell is: the chip, the gap, and one line of 12sp label.
 *
 * Derived rather than picked, because picking it is what broke the first version —
 * 68dp looked about right and was 10dp short of its own contents, so every label
 * in every deck lost its lower half. Stated as arithmetic so it stays visible, and
 * so changing the chip size cannot silently re-break it.
 */
internal val CELL_HEIGHT = CHIP_SIZE + CHIP_LABEL_GAP + LABEL_LINE_HEIGHT

/**
 * How tall [MacroTileNarrow]'s stack is, by the same arithmetic and for the same
 * reason — except that here there is nobody to ask for more room. A 1×1 tile gets
 * what the launcher gives it, and `RunTileWidget`'s smallest responsive bucket says
 * what the floor of that is, so this has to fit inside it with the chip already
 * shrunk. `MacroTileSizeTest` pins that it does.
 */
internal val NARROW_CONTENT_HEIGHT = CHIP_SIZE_NARROW + NARROW_LABEL_GAP + NARROW_LABEL_LINE_HEIGHT
