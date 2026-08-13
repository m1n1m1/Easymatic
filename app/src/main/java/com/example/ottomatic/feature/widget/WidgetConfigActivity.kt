package com.example.ottomatic.feature.widget

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.editorSwitchColors
import com.example.ottomatic.feature.macro.MacroIconChip
import com.example.ottomatic.ui.theme.OttomaticTheme
import kotlinx.coroutines.launch

/**
 * The screens the launcher opens when a widget is dropped, and again when one is
 * reconfigured.
 *
 * These are **in-app screens**, so they wear the app's fixed dark palette rather
 * than the widget's Material You — `OttomaticTheme(darkTheme = true,
 * dynamicColor = false)`, exactly as `MainActivity` does. The widget follows the
 * system because it lives on the home screen; a configuration screen is the app,
 * and switching palette halfway through placing a widget would read as two
 * different products.
 *
 * Both finish with `RESULT_CANCELED` unless the user confirms. That is not
 * politeness — it is the contract: a configuration activity that returns anything
 * else on back-press leaves a placed, unconfigured widget on the home screen, and
 * the launcher only removes it if the cancel is explicit.
 */
abstract class WidgetConfigActivity : ComponentActivity() {

    protected var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Set before anything can go wrong, so every exit that is not an explicit
        // confirm — back, a crash, the user swiping the task away — cancels.
        setResult(Activity.RESULT_CANCELED, resultIntent())

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            OttomaticTheme(darkTheme = true, dynamicColor = false) {
                Content()
            }
        }
    }

    @Composable
    protected abstract fun Content()

    /**
     * Applies [apply] to this widget's Glance state, then hands the id back.
     *
     * Guarded because `getGlanceIdBy` throws for an id the launcher no longer
     * knows, and it genuinely can: the user can cancel the placement from the
     * launcher's side while this screen is open. Falling through to [finish] leaves
     * the result as the `RESULT_CANCELED` set in [onCreate], which tells the
     * launcher to drop the widget — the right outcome for a configuration that
     * could not be written.
     */
    @Suppress("TooGenericExceptionCaught") // Any failure to reach the widget means the same thing.
    protected fun confirm(apply: suspend (androidx.glance.GlanceId) -> Unit) {
        lifecycleScope.launch {
            try {
                val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity)
                    .getGlanceIdBy(appWidgetId)
                apply(glanceId)
                setResult(Activity.RESULT_OK, resultIntent())
            } catch (e: Exception) {
                Log.w("Ottomatic", "Could not configure widget $appWidgetId", e)
            }
            finish()
        }
    }

    private fun resultIntent() =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

/**
 * Picks the one manual trigger a Run tile is a button for.
 *
 * **Unless the answer already came with the widget.** A tile pinned from the
 * workflow list was placed *for* a trigger, and some launchers open this screen
 * afterwards anyway — so the first thing it does is look for a key [RunTilePin] left
 * in the widget's options, and confirm with it rather than asking a question that has
 * already been answered. Nothing is drawn while that happens: `triggers` stays null,
 * which is the same "still loading" state this screen has always had, so the chooser
 * never flashes up on its way to closing.
 */
class RunTileConfigActivity : WidgetConfigActivity() {

    @Composable
    override fun Content() {
        var triggers by remember { mutableStateOf<List<ManualTriggerRef>?>(null) }
        LaunchedEffect(Unit) {
            val requested = RunTilePin.consumeRequestedKey(this@RunTileConfigActivity, appWidgetId)
            if (requested != null) {
                confirm { id -> setRunTileTrigger(this@RunTileConfigActivity, id, requested) }
                return@LaunchedEffect
            }
            triggers = MacroSnapshots.triggers()
        }

        ConfigScaffold(title = stringResource(R.string.widget_choose_a_trigger)) {
            val loaded = triggers
            when {
                loaded == null -> Unit
                loaded.isEmpty() -> EmptyTriggers()
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(loaded, key = { it.key }) { trigger ->
                        TriggerRow(
                            trigger = trigger,
                            // Tapping *is* the confirm here. A one-choice screen
                            // with a separate Save button asks for two taps to
                            // express one decision.
                            onClick = {
                                confirm { id ->
                                    setRunTileTrigger(this@RunTileConfigActivity, id, trigger.key)
                                }
                            },
                        )
                        HorizontalDivider(color = EditorColors.chromeBorder, thickness = 1.dp)
                    }
                }
            }
        }
    }
}

/**
 * Chooses what one panel shows.
 *
 * **It loads the panel's current configuration first**, and that is the fix for the
 * bug this screen used to have. The previous version initialised its switches to
 * hard-coded defaults, so reopening it showed every option reset — and Save then
 * wrote those resets back, which is why a header that had been turned off came
 * back on and a hand-picked trigger set was forgotten. [loadPanelConfig] is the
 * read that was missing; [PanelConfig] owns both directions so it cannot go
 * missing again.
 */
class PanelConfigActivity : WidgetConfigActivity() {

    @Composable
    override fun Content() {
        var loaded by remember { mutableStateOf(false) }
        var triggers by remember { mutableStateOf<List<ManualTriggerRef>?>(null) }

        var showStatus by remember { mutableStateOf(true) }
        var showProblems by remember { mutableStateOf(true) }
        var showLastRun by remember { mutableStateOf(true) }
        var showTriggers by remember { mutableStateOf(true) }
        var triggersAuto by remember { mutableStateOf(true) }
        val picked = remember { mutableStateListOf<String>() }

        LaunchedEffect(Unit) {
            val config = loadPanelConfig(this@PanelConfigActivity, appWidgetId)
            showStatus = config.showStatus
            showProblems = config.showProblems
            showLastRun = config.showLastRun
            showTriggers = config.showTriggers
            triggersAuto = config.triggersAuto
            picked.clear()
            picked.addAll(config.triggerKeys)
            triggers = MacroSnapshots.triggers()
            loaded = true
        }

        ConfigScaffold(
            title = stringResource(R.string.widget_ottomatic_panel),
            action = {
                Button(
                    onClick = {
                        val config = PanelConfig(
                            showStatus = showStatus,
                            showProblems = showProblems,
                            showLastRun = showLastRun,
                            showTriggers = showTriggers,
                            triggersAuto = triggersAuto,
                            triggerKeys = picked.toList(),
                        )
                        confirm { id -> savePanelConfig(this@PanelConfigActivity, id, config) }
                    },
                    // Both colours, not just the container. Leaving the content
                    // colour to Material resolves it against the app's scheme,
                    // which is still the Android Studio template's — so the label
                    // came out purple on a blue button.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EditorColors.actionAccent,
                        contentColor = EditorColors.textPrimary,
                        disabledContainerColor = EditorColors.nodeBorder,
                        disabledContentColor = EditorColors.textSecondary,
                    ),
                    // Only blocked on the one combination that cannot mean
                    // anything: picked mode with nothing picked would render an
                    // empty grid and look broken.
                    enabled = loaded && !(showTriggers && !triggersAuto && picked.isEmpty()),
                ) { Text(stringResource(R.string.widget_save)) }
            },
        ) {
            if (!loaded) return@ConfigScaffold
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    SectionLabel(stringResource(R.string.widget_show))
                    ToggleRow(
                        label = stringResource(R.string.widget_engine_status),
                        hint = stringResource(R.string.widget_whether_the_engine_is_running),
                        checked = showStatus,
                        onChange = { showStatus = it },
                    )
                    ToggleRow(
                        label = stringResource(R.string.widget_problems),
                        hint = stringResource(R.string.widget_a_count_of_macros_that),
                        checked = showProblems,
                        onChange = { showProblems = it },
                    )
                    ToggleRow(
                        label = stringResource(R.string.widget_last_run),
                        hint = stringResource(R.string.widget_which_macro_ran_last_whether),
                        checked = showLastRun,
                        onChange = { showLastRun = it },
                    )
                    ToggleRow(
                        label = stringResource(R.string.widget_trigger_buttons),
                        hint = stringResource(R.string.widget_a_grid_of_manual_triggers),
                        checked = showTriggers,
                        onChange = { showTriggers = it },
                    )
                }
                if (showTriggers) {
                    item {
                        SectionLabel(stringResource(R.string.widget_which_triggers))
                        ToggleRow(
                            label = stringResource(R.string.widget_show_all_of_them),
                            hint = stringResource(R.string.widget_new_macros_appear_on_the),
                            checked = triggersAuto,
                            onChange = { triggersAuto = it },
                        )
                        HorizontalDivider(color = EditorColors.chromeBorder, thickness = 1.dp)
                    }
                    val list = triggers.orEmpty()
                    when {
                        list.isEmpty() -> item { EmptyTriggers() }
                        triggersAuto -> item {
                            Hint(stringResource(R.string.widget_showing_all, list.size))
                        }

                        else -> items(list, key = { it.key }) { trigger ->
                            TriggerRow(
                                trigger = trigger,
                                // The number is the order: picked cells appear on
                                // the panel in the order they were tapped, so a
                                // plain tick would hide half of what the tap did.
                                selectionIndex = picked.indexOf(trigger.key).takeIf { it >= 0 },
                                onClick = {
                                    if (!picked.remove(trigger.key)) picked.add(trigger.key)
                                },
                            )
                            HorizontalDivider(color = EditorColors.chromeBorder, thickness = 1.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigScaffold(
    title: String,
    action: @Composable () -> Unit = {},
    body: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(EditorColors.chrome)
                .statusBarsPadding()
                .height(60.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = EditorColors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            action()
        }
        Box(modifier = Modifier.weight(1f)) { body() }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = EditorColors.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = EditorColors.textSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
    )
}

@Composable
private fun TriggerRow(
    trigger: ManualTriggerRef,
    onClick: () -> Unit,
    selectionIndex: Int? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacroIconChip(icon = trigger.icon, accent = trigger.accent)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = trigger.label,
                color = EditorColors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            // The macro's name is repeated under the label only when they differ.
            // For a macro with one unnamed trigger they are the same string, and
            // printing it twice makes the row look like it says two things.
            if (trigger.macroName != trigger.label) {
                Text(text = trigger.macroName, color = EditorColors.textSecondary, fontSize = 12.sp)
            }
        }
        if (selectionIndex != null) {
            Box(
                modifier = Modifier
                    .background(EditorColors.actionAccent, RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = (selectionIndex + 1).toString(),
                    color = EditorColors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = EditorColors.textPrimary, fontSize = 15.sp)
            Text(text = hint, color = EditorColors.textSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = editorSwitchColors())
    }
}

@Composable
private fun EmptyTriggers() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.widget_no_manual_triggers_yet_n),
            color = EditorColors.textSecondary,
            fontSize = 13.sp,
        )
    }
}
