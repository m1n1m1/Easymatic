package com.example.ottomatic.feature.home

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import com.example.ottomatic.feature.BottomNavigationBar
import com.example.ottomatic.feature.BottomNavigationBarIcon
import com.example.ottomatic.feature.BottomNavigationBarItem
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.setup.SetupScreen
import com.example.ottomatic.feature.workflowlist.WorkflowListScreen
import com.example.ottomatic.feature.workflowlist.WorkflowListViewModel

/**
 * The app's home: a navigation bar with the macro list above it, or everything that
 * is set up once and shared by every macro.
 *
 * ### One destination, two tabs
 *
 * This is a single `NavHost` destination that swaps its own content, not two
 * destinations sharing a bar — the same shape [com.example.ottomatic.feature.grapheditor.EditorTabPanel]
 * takes, and for the same reason: a bottom navigation bar *means* that the bar stays
 * put and the surface above it changes. Two nav destinations would also have made
 * `MainActivity`'s slide transitions fire on every tab tap, where what they say is
 * "you have left home for a screen of its own".
 *
 * The setup callbacks pass straight through to [SetupScreen]. They arrive here rather
 * than being resolved here because navigation belongs to whoever owns the
 * `NavController`, which is `MainActivity`.
 *
 * ### What the workflow list gave up
 *
 * Ten of these callbacks used to hang off the workflow list's title row — two icon
 * buttons and an eight-item overflow — which that file's own comment already declared
 * full. They are rows on the Setup tab now, and the row they vacated is the search
 * bar.
 */
@Composable
@Suppress("LongParameterList") // One parameter per destination the Setup tab lists.
fun HomeScreen(
    listViewModel: WorkflowListViewModel,
    onOpenWorkflow: (String) -> Unit,
    onOpenSmartHome: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenMailAccounts: () -> Unit,
    onOpenVariables: () -> Unit,
    onOpenGeofences: () -> Unit,
    onOpenNfcTags: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenAppAccess: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.WORKFLOWS) }
    // Material's rule for bottom navigation: back from any other tab returns to the
    // first one, and only from there does back leave the app.
    BackHandler(enabled = tab != HomeTab.WORKFLOWS) { tab = HomeTab.WORKFLOWS }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { homeTabTransition() },
            label = "homeTab",
            // weight(1f), never fillMaxSize(): the latter would measure the bar below
            // to nothing. Same rule as the editor's bottom bar.
            modifier = Modifier.weight(1f),
        ) { current ->
            when (current) {
                HomeTab.WORKFLOWS -> WorkflowListScreen(
                    viewModel = listViewModel,
                    onOpenWorkflow = onOpenWorkflow,
                )
                HomeTab.SETUP -> SetupScreen(
                    onOpenSmartHome = onOpenSmartHome,
                    onOpenAi = onOpenAi,
                    onOpenMailAccounts = onOpenMailAccounts,
                    onOpenVariables = onOpenVariables,
                    onOpenGeofences = onOpenGeofences,
                    onOpenNfcTags = onOpenNfcTags,
                    onOpenFolders = onOpenFolders,
                    onOpenPermissions = onOpenPermissions,
                    onOpenPlugins = onOpenPlugins,
                    onOpenAppAccess = onOpenAppAccess,
                )
            }
        }
        HomeBottomBar(selected = tab, onSelect = { tab = it })
    }
}

/** The app's two top-level places. Order is the order of the bar's items. */
enum class HomeTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    WORKFLOWS(R.string.workflowlist_workflows, Icons.AutoMirrored.Filled.FormatListBulleted),
    SETUP(R.string.setup_title, Icons.Filled.Tune),
}

/**
 * The app's navigation bar: [BottomNavigationBar] with this screen's two tabs in it.
 *
 * It used to be a full-height `NavigationBar` while the editor's was a
 * `ShortNavigationBar`, on the argument that the editor's is squeezed because it
 * sits over a canvas that wants every pixel and nothing here is under that pressure.
 * The pressure was the wrong test: the 16 dp is padding above and below the label,
 * and there is no screen in this app where a taller bar says more. Both are the
 * short one now, and the identical component is what stops them drifting apart
 * again.
 */
@Composable
private fun HomeBottomBar(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    BottomNavigationBar {
        HomeTab.entries.forEach { tab ->
            BottomNavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                label = stringResource(tab.labelRes),
                icon = { BottomNavigationBarIcon(tab.icon) },
            )
        }
    }
}

/**
 * How the region above the bar moves when the tab changes: horizontally, following
 * the order of the bar's items, so tapping something to the right of what is open
 * brings it in from the right.
 *
 * This is the horizontal half of the editor's `surfaceTransition`, and deliberately a
 * sibling of it rather than a shared generic — that one is typed to `EditorTab?` and
 * carries two vertical cases for a surface rising over the canvas, neither of which
 * happens here. Nothing is being covered on this screen; you are stepping along a
 * row, and the tween matches so that stepping feels the same in both places.
 */
private fun AnimatedContentTransitionScope<HomeTab>.homeTabTransition(): ContentTransform {
    val rightwards = targetState.ordinal > initialState.ordinal
    return ContentTransform(
        targetContentEnter = slideInHorizontally(tween(TAB_SWITCH_MS)) { if (rightwards) it else -it },
        initialContentExit = slideOutHorizontally(tween(TAB_SWITCH_MS)) { if (rightwards) -it else it },
        targetContentZIndex = 1f,
    )
}

/** Matched to the editor's tab switch, so a step sideways costs the same everywhere. */
private const val TAB_SWITCH_MS = 200
