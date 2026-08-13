package com.example.ottomatic.feature.shortcut

import com.example.ottomatic.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.example.ottomatic.core.service.RunFeedback
import com.example.ottomatic.domain.model.MacroAccent
import com.example.ottomatic.domain.model.MacroIcon
import com.example.ottomatic.feature.macro.colorRes
import com.example.ottomatic.feature.macro.drawableRes
import com.example.ottomatic.feature.widget.ManualTriggerRef
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat

/**
 * Manual triggers as launcher shortcuts — the other half of "one tap from the
 * home screen", for people who long-press an app icon rather than look at the home
 * screen itself.
 *
 * **Dynamic only.** These appear under a long-press of the app icon and are chosen
 * by the app: the few triggers you used most recently, kept current without anybody
 * configuring anything. They are the one thing a widget cannot be — a widget has to
 * be placed, and this list maintains itself.
 *
 * There were **pinned** shortcuts too, chosen by the user and living on the home
 * screen as their own icon, and "Add to home screen" in the workflow list used to
 * create one. It places a Run tile instead as of 2026-08-06, because the two occupy
 * the same grid cell and the tile is the one that can say anything: a pinned
 * shortcut is a bitmap the launcher owns from the moment it is dropped, so it cannot
 * report that a run is under way, that it failed, or that the macro is switched off.
 * See `RunTilePin`.
 */
object MacroShortcuts {

    /**
     * Republishes the dynamic shortcuts.
     *
     * Ranked most-recently-run first, from [RunFeedback], falling back to the
     * order the macros are listed in. Recency rather than a user-picked order
     * because a shortcut list nobody configured has to guess, and "the thing you
     * just used" is by far the best guess available — it is also what makes the
     * list improve on its own instead of ossifying around whatever existed first.
     *
     * Capped at [MAX_DYNAMIC] and at what the launcher will accept. Most launchers
     * show four or five, and pushing more than are displayed is work that produces
     * nothing visible.
     */
    fun pushDynamic(context: Context, triggers: List<ManualTriggerRef>) {
        val ranked = rank(triggers, RunFeedback.entries.value)
        val limit = minOf(MAX_DYNAMIC, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))
        val shortcuts = ranked.take(limit).mapIndexed { rank, trigger -> build(context, trigger, rank) }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    /** Drops every dynamic shortcut. Used when the last manual trigger disappears. */
    fun clearDynamic(context: Context) {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }

    private fun rank(
        triggers: List<ManualTriggerRef>,
        feedback: Map<String, RunFeedback.Entry>,
    ): List<ManualTriggerRef> = triggers.sortedByDescending { feedback[it.key]?.atMs ?: 0L }

    private fun build(context: Context, trigger: ManualTriggerRef, rank: Int) =
        ShortcutInfoCompat.Builder(context, trigger.key)
            .setShortLabel(trigger.label)
            // The long label gets the macro's name when it differs, because a
            // launcher with room for it should say which macro "Start" belongs to.
            .setLongLabel(
                if (trigger.macroName == trigger.label) {
                    trigger.label
                } else {
                    context.getString(R.string.shortcut_macro_and_trigger, trigger.macroName, trigger.label)
                },
            )
            .setIcon(adaptiveIcon(context, trigger.icon, trigger.accent))
            .setIntent(
                RunTriggerActivity.intent(context, trigger.workflowId, trigger.nodeId, trigger.label),
            )
            .setRank(rank)
            .build()

    /**
     * The macro's glyph, white, on a filled circle of its accent, as an adaptive
     * icon.
     *
     * Not `IconCompat.createWithResource(icon.drawableRes())`, which is the obvious
     * one-liner and looks wrong on every launcher: a bare vector is rendered as a
     * thin monochrome glyph on the launcher's own grey badge, so eight pinned
     * macros become eight grey circles and the colour the user chose never appears.
     * Drawing it here is what makes a pinned trigger look like an app icon rather
     * than like a system setting.
     *
     * The circle is inset to [SAFE_ZONE_RATIO] of the bitmap because an adaptive
     * icon's outer edges are the mask's to crop — a shape drawn to the full bounds
     * loses its rim on any launcher using a circle or a squircle.
     */
    private fun adaptiveIcon(context: Context, icon: MacroIcon, accent: MacroAccent): IconCompat {
        val size = ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val accentColor = accent.colorRes()
            ?.let { ContextCompat.getColor(context, it) }
            ?: ContextCompat.getColor(context, com.example.ottomatic.R.color.dialog_accent)

        val radius = size * SAFE_ZONE_RATIO / 2f
        canvas.drawCircle(
            size / 2f,
            size / 2f,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor },
        )

        val glyphSize = (radius * GLYPH_RATIO).toInt()
        ResourcesCompat.getDrawable(context.resources, icon.drawableRes(), context.theme)?.let { drawable ->
            val glyph = drawable.toBitmap(glyphSize, glyphSize)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = android.graphics.PorterDuffColorFilter(
                    Color.WHITE,
                    android.graphics.PorterDuff.Mode.SRC_IN,
                )
            }
            canvas.drawBitmap(glyph, (size - glyphSize) / 2f, (size - glyphSize) / 2f, paint)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    /**
     * Four, not the five or so a launcher will accept.
     *
     * The long-press menu also carries the launcher's own entries — App info,
     * Uninstall, Widgets — and a menu that fills the screen is one people stop
     * reading. Four is enough to cover the macros somebody actually runs by hand.
     */
    private const val MAX_DYNAMIC = 4

    /** 108dp at xxxhdpi, the adaptive-icon canvas. */
    private const val ICON_SIZE_PX = 432

    /** The 72/108 of an adaptive icon that no mask may crop. */
    private const val SAFE_ZONE_RATIO = 72f / 108f

    private const val GLYPH_RATIO = 1.1f
}
