package com.example.ottomatic.feature.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * What one placed panel shows.
 *
 * A single type that **reads and writes itself**, rather than a bag of preference
 * keys each screen dips into. That is the fix for a real bug rather than tidiness:
 * the previous config activity initialised its switches to hard-coded defaults and
 * never read the stored state back, so reopening it showed every option reset —
 * and because Save then wrote those resets, an option you had turned off came back
 * on. Both halves of that ("the header setting is not stored", "the trigger
 * selection is not restored") were the same missing read.
 *
 * With the state behind [from] and [writeTo], a screen cannot read a key the
 * widget writes under a different name, and cannot forget to load one at all —
 * there is one place that knows the keys and it does both directions.
 *
 * Every flag defaults to **on**. A panel dropped without configuring it should
 * show everything it can; hiding is the choice, and it is one the user makes.
 */
data class PanelConfig(
    val showStatus: Boolean = true,
    val showProblems: Boolean = true,
    val showLastRun: Boolean = true,
    val showTriggers: Boolean = true,
    /**
     * Auto mode shows every manual trigger there is; picked mode shows [triggerKeys].
     *
     * Auto is the default, and the default matters more than it looks: in auto a
     * macro created next week appears on the panel with no further action, while a
     * picked set quietly stops representing the app.
     */
    val triggersAuto: Boolean = true,
    /** Picked mode's chosen trigger keys, in the order they were chosen. */
    val triggerKeys: List<String> = emptyList(),
) {

    fun writeTo(prefs: MutablePreferences) {
        prefs[SHOW_STATUS] = showStatus
        prefs[SHOW_PROBLEMS] = showProblems
        prefs[SHOW_LAST_RUN] = showLastRun
        prefs[SHOW_TRIGGERS] = showTriggers
        prefs[TRIGGERS_AUTO] = triggersAuto
        prefs[TRIGGER_KEYS] = triggerKeys.joinToString(SEPARATOR)
    }

    /** True when the panel would render nothing at all. */
    val isEmpty: Boolean
        get() = !showStatus && !showProblems && !showLastRun && !showTriggers

    companion object {
        fun from(prefs: Preferences): PanelConfig = PanelConfig(
            showStatus = prefs[SHOW_STATUS] ?: true,
            showProblems = prefs[SHOW_PROBLEMS] ?: true,
            showLastRun = prefs[SHOW_LAST_RUN] ?: true,
            showTriggers = prefs[SHOW_TRIGGERS] ?: true,
            triggersAuto = prefs[TRIGGERS_AUTO] ?: true,
            triggerKeys = parseKeys(prefs[TRIGGER_KEYS]),
        )

        /**
         * Newline-separated, because a trigger key is `workflowId:nodeId` and
         * already contains a colon — and a workflow id is a UUID, which rules out
         * a comma no more safely.
         */
        private fun parseKeys(raw: String?): List<String> =
            raw?.split(SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        private val SHOW_STATUS = booleanPreferencesKey("showStatus")
        private val SHOW_PROBLEMS = booleanPreferencesKey("showProblems")
        private val SHOW_LAST_RUN = booleanPreferencesKey("showLastRun")
        private val SHOW_TRIGGERS = booleanPreferencesKey("showTriggers")
        private val TRIGGERS_AUTO = booleanPreferencesKey("triggersAuto")
        private val TRIGGER_KEYS = stringPreferencesKey("triggerKeys")
        private const val SEPARATOR = "\n"
    }
}

/**
 * The config the panel [appWidgetId] is currently showing.
 *
 * This is the read the previous version never did. `getAppWidgetState` on a widget
 * the launcher has not finished binding throws, so the failure is caught and the
 * defaults returned — a freshly-dropped panel has no stored state, and defaults
 * are exactly what it should be configured from.
 */
@Suppress("TooGenericExceptionCaught") // An unreadable state and an absent one mean the same thing here.
suspend fun loadPanelConfig(context: Context, appWidgetId: Int): PanelConfig = try {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
    PanelConfig.from(getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId))
} catch (e: Exception) {
    // Logged rather than swallowed: an unbound widget is the expected case and
    // says nothing, but a state file that exists and cannot be read is a
    // configuration silently reverting to defaults, which is exactly the bug this
    // function was added to fix and should not be invisible a second time.
    Log.d("Ottomatic", "No stored config for widget $appWidgetId; using defaults", e)
    PanelConfig()
}

/** Applies [config] to one placed panel and redraws it. */
suspend fun savePanelConfig(context: Context, glanceId: GlanceId, config: PanelConfig) {
    updateAppWidgetState(context, glanceId) { prefs -> config.writeTo(prefs) }
    PanelWidget().update(context, glanceId)
}

/**
 * The Run tile's single trigger. Kept separate from [PanelConfig] because the two
 * widgets share no options: a Run tile is one button and has nothing to hide.
 */
val RUN_TILE_TRIGGER_KEY: Preferences.Key<String> = stringPreferencesKey("triggerKey")
