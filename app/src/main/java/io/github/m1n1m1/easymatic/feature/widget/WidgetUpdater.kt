package io.github.m1n1m1.easymatic.feature.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import io.github.m1n1m1.easymatic.ServiceLocator
import io.github.m1n1m1.easymatic.core.service.RunFeedback
import io.github.m1n1m1.easymatic.engine.service.MacroEngineService
import io.github.m1n1m1.easymatic.feature.shortcut.MacroShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Keeps the three widgets showing the truth.
 *
 * ## Why this observes rather than being called
 *
 * The obvious design is for the repository to redraw the widgets when it writes,
 * and the engine to redraw them when a run ends. Both are forbidden: the package
 * rule is `data ← domain + core` and `engine ← domain + core`, and widgets are
 * `feature/`. Neither of the two components that *know* something changed is
 * allowed to know that widgets exist.
 *
 * So the traffic runs the other way. `WorkflowRepository.changes` and
 * [RunFeedback] are both plain flows in packages `feature` may depend on, this
 * collects them, and [attach] is called from `EasymaticApplication` — the root
 * package, which is the one place allowed to see everything and whose job is
 * exactly this kind of wiring.
 *
 * ## Debounce
 *
 * Every source here arrives in bursts: saving a graph writes once per debounced
 * edit, and a macro that finishes emits a RUNNING and a DONE a moment apart.
 * Redrawing a widget is a `RemoteViews` build and an IPC to the launcher, so the
 * bursts are collapsed rather than followed.
 */
object WidgetUpdater {

    @Volatile
    private var attached = false

    /**
     * Starts observing. Idempotent, because `Application.onCreate` is not the only
     * thing that could reasonably want to call it and a second set of collectors
     * would double every redraw.
     */
    @OptIn(FlowPreview::class)
    fun attach(context: Context, scope: CoroutineScope) {
        if (attached) return
        attached = true
        val appContext = context.applicationContext

        // A stored workflow changed: names, icons, enabled flags and the very set of
        // manual triggers may all be different, so the cache goes first.
        scope.launch {
            ServiceLocator.workflowRepository.changes
                .debounce(DEBOUNCE_MS)
                .collect {
                    MacroSnapshots.invalidate()
                    updateAllWidgets(appContext)
                    refreshShortcuts(appContext)
                }
        }

        // A run started or ended, or the engine came up or went down. `drop(1)`
        // because a StateFlow replays its current value to every new collector, and
        // the first emission here is just "this is what it already was" — redrawing
        // every widget on process start for that is work nobody asked for.
        scope.launch {
            merge(
                RunFeedback.entries.drop(1),
                MacroEngineService.engineRunning.drop(1),
                MacroEngineService.armedCount.drop(1),
            )
                .debounce(DEBOUNCE_MS)
                .collect {
                    updateAllWidgets(appContext)
                    // Dynamic shortcuts are ranked by recency, so a finished run
                    // changes their order. Republishing here is what keeps the
                    // long-press menu offering the macro you just used rather than
                    // the one you used last month.
                    refreshShortcuts(appContext)
                }
        }

        // A finished run decays back to idle after RunFeedback.SETTLE_MS, and
        // nothing emits when that happens — the decay is a function of the clock,
        // not an event. Without this one delayed redraw a tile would keep saying
        // "Done" until something else happened to redraw it.
        scope.launch {
            RunFeedback.entries.drop(1).collect {
                delay(RunFeedback.SETTLE_MS + SETTLE_MARGIN_MS)
                updateAllWidgets(appContext)
            }
        }
    }

    private const val DEBOUNCE_MS = 250L

    /** Enough that the decay has certainly passed by the time the redraw is built. */
    private const val SETTLE_MARGIN_MS = 250L
}

/**
 * Republishes the launcher's dynamic shortcuts from the current triggers.
 *
 * Clearing when there are none is not the same as publishing an empty list by
 * accident: a macro whose manual trigger was just deleted must not leave a
 * long-press entry that opens a trampoline for a node that is gone.
 */
private suspend fun refreshShortcuts(context: Context) {
    val triggers = MacroSnapshots.triggers()
    if (triggers.isEmpty()) {
        MacroShortcuts.clearDynamic(context)
    } else {
        MacroShortcuts.pushDynamic(context, triggers)
    }
}

/**
 * Redraws every placed widget of both kinds.
 *
 * `updateAll` is a no-op for a kind nobody has placed, so asking for both is
 * cheaper than working out which kinds exist. The `runCatching` is for the window
 * during which a widget is being removed: the launcher can drop an id between this
 * enumerating it and Glance writing to it, and a tile that no longer exists is not
 * an error worth propagating into whatever triggered the redraw.
 */
suspend fun updateAllWidgets(context: Context) {
    val appContext = context.applicationContext
    runCatching {
        RunTileWidget().updateAll(appContext)
        PanelWidget().updateAll(appContext)
    }.onFailure { Log.w("Easymatic", "Could not update widgets", it) }
}
