package com.example.ottomatic.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.example.ottomatic.ServiceLocator
import kotlinx.coroutines.launch

/**
 * "Add to home screen", for a manual trigger — as a **Run tile widget**.
 *
 * It used to be a pinned launcher shortcut, and the two are the same idea reached
 * two different ways: one grid cell that runs one macro. The widget wins because a
 * shortcut is an *icon*, and an icon is all a launcher will ever let it be — the
 * app draws a glyph on a coloured circle into a bitmap — `MacroShortcuts` still does
 * exactly that for the long-press menu — and from then on the home screen owns it.
 * It cannot say "Running…", it cannot go red when a run fails, it cannot show that
 * the macro is switched off, and its label is the launcher's to truncate. A Run tile
 * does all four, is already built, and is resizable into the wide form the moment
 * the user wants a name beside the icon rather than under it.
 *
 * ## Placing it without asking twice
 *
 * [RunTileConfigActivity] exists because a tile dropped from the widget picker has
 * to be told which trigger it is for. Pinning from the workflow list has *already*
 * been told, so being asked again would be the same question twice — but whether the
 * launcher opens that screen after a pin is the launcher's decision and nothing in
 * the API lets us say "don't". Both outcomes are therefore covered, and the trigger
 * key travels by two routes to do it:
 *
 *  - in the **options bundle** of the pin request, which the launcher stores against
 *    the new widget id. [consumeRequestedKey] is where the config screen finds it
 *    and confirms itself without drawing anything.
 *  - in the **success callback**, a broadcast the launcher fires once the widget
 *    exists, carrying the id it was given. [RunTilePinnedReceiver] writes the trigger
 *    from there for the launchers that never open the config screen at all.
 *
 * Whichever arrives first wins and the other becomes a no-op: the receiver writes
 * only into a tile that has no trigger yet, so it can never overrule a choice the
 * user made on the config screen in the meantime.
 *
 * The requested key is **consumed**, not merely read. It is stored in the widget's
 * options, which outlive the placement, so a key left there would be found again the
 * next time the user reconfigured that tile — and reconfiguring would silently
 * re-apply the original trigger instead of offering the list.
 */
object RunTilePin {

    /**
     * Asks the launcher to place a Run tile for [trigger].
     *
     * Returns false when the launcher does not support pinning — several do not, and
     * the caller says so rather than leaving the user waiting for a system dialog
     * that is never going to appear.
     */
    fun request(context: Context, trigger: ManualTriggerRef): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (manager == null || !manager.isRequestPinAppWidgetSupported) return false
        val provider = ComponentName(context.applicationContext, RunTileReceiver::class.java)
        return runCatching {
            manager.requestPinAppWidget(
                provider,
                Bundle().apply { putString(EXTRA_TRIGGER_KEY, trigger.key) },
                callback(context, trigger),
            )
        }.getOrDefault(false)
    }

    /**
     * The trigger a pin request asked for, removed from the widget's options as it is
     * read. Null for a tile that arrived any other way — from the widget picker, or
     * on a launcher that dropped the options bundle.
     */
    fun consumeRequestedKey(context: Context, appWidgetId: Int): String? {
        val manager = AppWidgetManager.getInstance(context)
        val key = runCatching { manager?.getAppWidgetOptions(appWidgetId)?.getString(EXTRA_TRIGGER_KEY) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (key != null) {
            // Null rather than absent, because updateAppWidgetOptions merges into
            // the stored bundle and has no way to remove a key. A null string reads
            // back as no key, which is what "consumed" has to mean here.
            runCatching {
                manager.updateAppWidgetOptions(
                    appWidgetId,
                    Bundle().apply { putString(EXTRA_TRIGGER_KEY, null) },
                )
            }
        }
        return key
    }

    /**
     * The broadcast the launcher fires once the widget exists.
     *
     * **Mutable**, which is the one flag here that is not a formality: the system
     * fills [AppWidgetManager.EXTRA_APPWIDGET_ID] into this intent before sending it,
     * and an immutable PendingIntent would arrive with no id to write to. The request
     * code is the trigger's, so pinning a second macro before the first callback has
     * fired cannot have `FLAG_UPDATE_CURRENT` rewrite the first one's payload.
     */
    private fun callback(context: Context, trigger: ManualTriggerRef): PendingIntent {
        val intent = Intent(context.applicationContext, RunTilePinnedReceiver::class.java)
            .putExtra(EXTRA_TRIGGER_KEY, trigger.key)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            trigger.key.hashCode(),
            intent,
            flags,
        )
    }

    internal const val EXTRA_TRIGGER_KEY = "com.example.ottomatic.RUN_TILE_TRIGGER"
}

/**
 * Points a freshly-pinned tile at the trigger it was pinned for.
 *
 * Not exported, and it does not need to be: a `PendingIntent` is sent with the
 * identity of the app that created it, so this arrives as a broadcast from ourselves
 * however far it travelled through the launcher on the way.
 *
 * `goAsync` because writing Glance state is suspending and a receiver that returns
 * before its coroutine runs is a process the system is free to kill mid-write. The
 * work goes to `appScope` rather than to a scope of its own, so it survives the
 * receiver returning.
 */
class RunTilePinnedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val triggerKey = intent.getStringExtra(RunTilePin.EXTRA_TRIGGER_KEY)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || triggerKey.isNullOrBlank()) {
            Log.w("Ottomatic", "Run tile pinned with no widget id or trigger; nothing to configure")
            return
        }
        val appContext = context.applicationContext
        val result = goAsync()
        ServiceLocator.appScope.launch {
            try {
                adoptRunTileTrigger(appContext, appWidgetId, triggerKey)
            } finally {
                result.finish()
            }
        }
    }
}

/**
 * Writes [triggerKey] into the tile [appWidgetId] — but **only if it has none**.
 *
 * The guard is what makes the two delivery routes safe to run at once. If the
 * launcher opened the config screen and the user chose something there, that choice
 * is already in the state by the time this runs, and a pin request is not entitled
 * to overwrite a decision made after it.
 *
 * Guarded for the same reason `WidgetConfigActivity.confirm` is: `getGlanceIdBy`
 * throws for an id the launcher has since dropped, which is an ordinary thing to
 * happen to a widget between being created and being written to.
 */
@Suppress("TooGenericExceptionCaught") // Any failure to reach the widget means the same thing.
private suspend fun adoptRunTileTrigger(context: Context, appWidgetId: Int, triggerKey: String) {
    // Consumed here as well as on the config screen: a launcher that never opens
    // that screen would otherwise leave the request sitting in the options, where
    // the first reconfigure would find it and quietly undo itself.
    RunTilePin.consumeRequestedKey(context, appWidgetId)
    try {
        val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
        val existing = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
        if (!existing[RUN_TILE_TRIGGER_KEY].isNullOrBlank()) return
        setRunTileTrigger(context, glanceId, triggerKey)
    } catch (e: Exception) {
        Log.w("Ottomatic", "Could not configure pinned Run tile $appWidgetId", e)
    }
}
