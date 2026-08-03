package com.example.ottomatic.feature.grapheditor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.engine.validation.GraphValidation
import com.example.ottomatic.feature.variables.VariableScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The editor's bottom bar: three items answering the three questions a macro raises
 * when it does not do what you meant.
 *
 * **Problems** is what the graph *is*, **Console** is what a run *did*, and
 * **Variables** is what the state *is right now*. All three used to be somewhere
 * else — two icons in a top bar that was already full, and, for variables, nowhere
 * at all. Here they cost the title bar nothing and their badges are permanently
 * visible, which is the point: a warning nobody can see until they open something
 * is not a warning.
 *
 * ### A standard navigation bar, in the editor's palette
 *
 * [ShortNavigationBar] rather than `NavigationBar`: same stacked icon-over-label
 * item, 64 dp instead of 80, because the difference between them is almost entirely
 * padding above and below the content. The component is still Material's, so the
 * touch targets, the ripple, the selection pill and the accessibility semantics
 * are too.
 *
 * Only the colours are overridden, from [EditorColors]. That is deliberate and not
 * an oversight: the editor's palette is fixed dark and independent of the app theme
 * (see [EditorColors]), so a bar taking `MaterialTheme.colorScheme` would render
 * light — under a permanently dark canvas — on any light-themed phone.
 *
 * ### The bar stays put; the surface above it changes
 *
 * This is what a bottom navigation bar means, and the reason none of the three
 * surfaces is a `Dialog` any more: a dialog window swallows every touch inside its
 * bounds, so a bar left visible underneath one would be visible and dead. Instead
 * [selected] is hoisted to `GraphEditorContent`, which swaps the canvas for
 * [EditorTabPanel] in the same region. The bar never moves, so switching from
 * Problems to Console is one tap rather than a dismiss and a reopen.
 *
 * Tapping the selected item again clears it and brings the canvas back; so does the
 * system back gesture. The three surfaces carry no chrome of their own — no title,
 * no close button — because the bar underneath already says which one is showing,
 * and every row of chrome is a row of findings not shown.
 *
 * ### Flows in, never lists
 *
 * Every `collectAsState` is at or below body level — the rule [ConsoleBody] and
 * [ProblemsBody] already state, and the reason the badges are their own
 * composables. `GraphEditorContent` collects nothing on this bar's behalf. Anyone
 * tempted to "simplify" that by hoisting a collection is about to repaint the whole
 * canvas once per log line.
 */
@Composable
fun EditorBottomBar(
    selected: EditorTab?,
    onSelect: (EditorTab?) -> Unit,
    validation: StateFlow<GraphValidation>,
    consoleProblems: StateFlow<Int>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        HorizontalDivider(color = EditorColors.chromeBorder)
        ShortNavigationBar(
            containerColor = EditorColors.chrome,
            contentColor = EditorColors.textPrimary,
            windowInsets = trimmedBottomInsets(),
        ) {
            EditorTab.entries.forEach { tab ->
                ShortNavigationBarItem(
                    selected = selected == tab,
                    onClick = { onSelect(tab.takeIf { it != selected }) },
                    icon = { TabIcon(tab = tab, validation = validation, consoleProblems = consoleProblems) },
                    label = { Text(tab.label, fontSize = 11.sp) },
                    colors = ShortNavigationBarItemDefaults.colors(
                        selectedIconColor = EditorColors.textPrimary,
                        selectedTextColor = EditorColors.textPrimary,
                        selectedIndicatorColor = EditorColors.nodeBackground,
                        unselectedIconColor = EditorColors.textSecondary,
                        unselectedTextColor = EditorColors.textSecondary,
                    ),
                )
            }
        }
    }
}

/**
 * How much of the system's bottom inset the bar keeps.
 *
 * The inset is padding *inside* the bar's surface, so it lands entirely below the
 * labels and makes the bar look bottom-heavy — the space above the icons is the
 * item's own 12 dp, the space below it is that plus the inset. Under gesture
 * navigation the inset guards a thin handle drawn *over* the app, and the bar's own
 * padding already keeps the labels clear of it, so half of it is enough and the bar
 * sits that much lower.
 *
 * Three-button navigation is left alone: there the inset guards real buttons that
 * would swallow taps meant for the bar, and its size is what says which one is in
 * use — a handle reserves a strip, a button bar reserves a bar.
 */
@Composable
private fun trimmedBottomInsets(): WindowInsets {
    val insets = WindowInsets.systemBars
    val bottom = with(LocalDensity.current) { insets.getBottom(this).toDp() }
    val kept = if (bottom <= GESTURE_HANDLE_MAX) bottom / 2 else bottom
    return insets.only(WindowInsetsSides.Horizontal).add(WindowInsets(bottom = kept))
}

/** Above this, a bottom inset is reserving buttons rather than a gesture handle. */
private val GESTURE_HANDLE_MAX: Dp = 32.dp

/**
 * Whichever surface the bar has selected, filling the region the canvas usually
 * occupies, under a top bar of its own.
 *
 * The top bar is what tells you which surface you are looking at once the enter
 * animation has finished and the slide that brought it in is over, and it is where
 * the per-surface actions live — Clear for the console, nothing for the other two.
 * Its ✕ is one of the three ways back to the canvas, beside the bar's own selected
 * item and the system back gesture.
 *
 * **Variables has a second level**, the shared library. It is a level rather than a
 * section of one list because this surface is about *this macro's* state, and it is
 * a level *here* rather than the standalone globals screen the workflow list opens
 * because leaving the editor and coming back would land on the canvas — the back
 * gesture has to return to the workflow's own variables, which means never having
 * left the editor. [showingGlobals] is therefore local state with its own
 * [BackHandler], registered below the screen's so it is offered the gesture first.
 *
 * [onSelectNode] and [onSelectConnection] are expected to clear the selected tab as
 * well as making the selection: picking a finding is a request to go and look at
 * the thing it names, and the canvas is where that is.
 */
@Composable
@Suppress("LongParameterList") // Three surfaces' worth of state; each parameter belongs to one of them.
fun EditorTabPanel(
    tab: EditorTab,
    workflow: Workflow,
    validation: StateFlow<GraphValidation>,
    console: StateFlow<List<LogEntry>>,
    consoleMinLevel: StateFlow<LogLevel>,
    variableValues: StateFlow<Map<String, String>>,
    onMinLevelChange: (LogLevel) -> Unit,
    onClearConsole: () -> Unit,
    onClose: () -> Unit,
    onSelectNode: (NodeId) -> Unit,
    onSelectConnection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Reset per surface: switching tabs and coming back should start where the
    // Variables surface is about, which is this workflow.
    var showingGlobals by remember(tab) { mutableStateOf(false) }
    BackHandler(enabled = showingGlobals) { showingGlobals = false }

    // Opaque, because the canvas is still composed underneath while the panel
    // slides in and must not show through it.
    Surface(modifier = modifier, color = EditorColors.canvasBackground) {
        Column {
            PanelTopBar(
                title = if (showingGlobals) "Global variables" else tab.label,
                onUp = { if (showingGlobals) showingGlobals = false else onClose() },
                upIcon = if (showingGlobals) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                upLabel = if (showingGlobals) "Back to this workflow's variables" else "Back to the graph",
                action = if (tab == EditorTab.CONSOLE) {
                    {
                        IconButton(onClick = onClearConsole) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = "Clear console",
                                tint = EditorColors.textSecondary,
                            )
                        }
                    }
                } else {
                    null
                },
            )
            when (tab) {
                EditorTab.PROBLEMS -> ProblemsBody(
                    validation = validation,
                    workflow = workflow,
                    onSelectNode = onSelectNode,
                    onSelectConnection = onSelectConnection,
                )
                EditorTab.CONSOLE -> ConsoleBody(
                    entries = console,
                    minLevel = consoleMinLevel,
                    onMinLevelChange = onMinLevelChange,
                    onSelectNode = onSelectNode,
                )
                EditorTab.VARIABLES -> VariablesBody(
                    variableValues = variableValues,
                    scope = if (showingGlobals) VariableScope.GLOBAL else VariableScope.LOCAL,
                    onOpenGlobals = { showingGlobals = true },
                )
            }
        }
    }
}

/**
 * A surface's own top bar, which **replaces** [EditorTopBar] rather than stacking
 * under it.
 *
 * The panel runs the full height above [EditorBottomBar], so this is the topmost
 * chrome while a surface is open and takes [BAR_HEIGHT] and `statusBarsPadding()`
 * to match the bar it stands in for exactly — a different height would make the
 * whole screen jump as a surface opened. It carries the same 6 dp / 12 dp insets
 * that `EditorTopBar` and `OverlayTopBar` use, so the ✕ and the title line up
 * wherever you are in the editor.
 *
 * Replacing rather than stacking is also what makes the enter animation read: the
 * surface rises over *everything* except the navigation bar, which is the same
 * gesture the full-screen overlays make.
 *
 * [upIcon] carries which of two things the leading button does, because a surface
 * can be one level deep: ✕ leaves for the graph, ← goes back a level within the
 * surface. Same button, and the icon is the only thing that says which.
 */
@Composable
fun PanelTopBar(
    title: String,
    onUp: () -> Unit,
    upIcon: ImageVector = Icons.Filled.Close,
    upLabel: String = "Back to the graph",
    action: @Composable (RowScope.() -> Unit)? = null,
) {
    Surface(color = EditorColors.chrome) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(BAR_HEIGHT)
                    .padding(start = 6.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onUp) {
                    Icon(
                        imageVector = upIcon,
                        contentDescription = upLabel,
                        tint = EditorColors.textPrimary,
                    )
                }
                Text(
                    text = title,
                    color = EditorColors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp),
                )
                action?.invoke(this)
            }
            HorizontalDivider(color = EditorColors.chromeBorder)
        }
    }
}

/** The bar's three surfaces. Order is the order they are read in when something is wrong. */
enum class EditorTab(val label: String, val icon: ImageVector) {
    PROBLEMS("Problems", Icons.Filled.ReportProblem),
    CONSOLE("Console", Icons.Filled.Terminal),
    VARIABLES("Variables", Icons.Filled.Tag),
}

/**
 * An item's icon, wrapped in whatever badge it earns.
 *
 * Each badge collects its own flow, so a count ticking during a run invalidates one
 * item rather than the bar it sits in. [ProblemsBadge] and [ConsoleBadge] already
 * take their content as a lambda around a `BadgedBox`, which is exactly what this
 * slot wants.
 */
@Composable
private fun TabIcon(
    tab: EditorTab,
    validation: StateFlow<GraphValidation>,
    consoleProblems: StateFlow<Int>,
) {
    val icon: @Composable () -> Unit = {
        Icon(imageVector = tab.icon, contentDescription = null, modifier = Modifier.size(22.dp))
    }
    when (tab) {
        EditorTab.PROBLEMS -> ProblemsBadge(validation, icon)
        EditorTab.CONSOLE -> ConsoleBadge(consoleProblems, icon)
        EditorTab.VARIABLES -> icon()
    }
}
