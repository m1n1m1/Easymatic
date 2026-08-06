package com.example.ottomatic.core.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What a manually-started run is doing, for the surfaces that are not the app.
 *
 * A home-screen tile is a button whose feedback has to come from somewhere, and
 * the run log is the wrong somewhere: it answers "what happened inside this
 * macro", in hundreds of lines, while a tile asks one question — did the thing I
 * just tapped work? — and has room for one glyph.
 *
 * It lives in `core/` because of who touches it. The engine service **writes**
 * here as it runs a macro; the widget layer in `feature/` **reads** here to
 * decide what to draw. Under the package rule (`engine ← domain + core`,
 * `feature ← domain + engine + core`) `core` is the only place both can see.
 *
 * Process-lifetime and in memory only. Nothing here is worth persisting: "this
 * finished four seconds ago" is meaningless after the process that observed it is
 * gone, and [lastRun] is re-established by the next run.
 */
object RunFeedback {

    /** What a tile draws. Absent from [displayState] means "nothing to report". */
    enum class State { RUNNING, DONE, FAILED }

    /**
     * One manual invocation's outcome.
     *
     * [macroName] is carried rather than looked up because the status widget shows
     * the last run's name and would otherwise have to load a workflow from disk to
     * render a line about one that already finished.
     */
    data class Entry(
        val state: State,
        val atMs: Long,
        val macroName: String,
        val triggerLabel: String,
    )

    /**
     * Which button was pressed, and what to call it.
     *
     * One value rather than four parameters because the four never travel apart:
     * every caller that knows the ids also knows the names, and both report and
     * both writes need all of them. `feature/`'s `ManualTriggerRef` is the same
     * information plus the appearance a widget draws — it cannot be used here,
     * since `core` may not see `feature`, which is the whole reason this type is
     * the smaller one.
     */
    data class Target(
        val workflowId: String,
        val nodeId: String,
        val macroName: String,
        val triggerLabel: String,
    ) {
        val key: String get() = keyOf(workflowId, nodeId)
    }

    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** Keyed by [keyOf]: the most recent invocation of each manual trigger. */
    val entries: StateFlow<Map<String, Entry>> = _entries.asStateFlow()

    private val _lastRun = MutableStateFlow<Entry?>(null)

    /** The most recent invocation of any manual trigger, for the status widget. */
    val lastRun: StateFlow<Entry?> = _lastRun.asStateFlow()

    /** A stable identity for one manual trigger, since a node id is only unique within its macro. */
    fun keyOf(workflowId: String, nodeId: String): String = "$workflowId:$nodeId"

    fun running(target: Target, nowMs: Long) {
        put(target, Entry(State.RUNNING, nowMs, target.macroName, target.triggerLabel))
    }

    fun finished(target: Target, ok: Boolean, nowMs: Long) {
        put(target, Entry(if (ok) State.DONE else State.FAILED, nowMs, target.macroName, target.triggerLabel))
    }

    private fun put(target: Target, entry: Entry) {
        _entries.update { it + (target.key to entry) }
        _lastRun.value = entry
    }

    /**
     * What [entry] should look like at [nowMs], or null once it has nothing left to
     * say.
     *
     * The decay is computed here rather than stored because a widget redraws for
     * reasons that have nothing to do with this — a resize, a theme change, another
     * macro finishing — and every one of those must show the truth rather than a
     * two-minute-old tick. [RUNNING] never decays: a run that is genuinely still
     * going should keep saying so, and the alternative is a tile that goes quiet
     * halfway through a macro with a five-minute delay in it.
     *
     * Pure, so `RunFeedbackTest` can pin it without a clock or a widget host.
     */
    fun displayState(entry: Entry?, nowMs: Long): State? = when {
        entry == null -> null
        entry.state == State.RUNNING -> State.RUNNING
        nowMs - entry.atMs <= SETTLE_MS -> entry.state
        else -> null
    }

    /**
     * How long a finished run keeps reporting itself.
     *
     * Long enough to be read after the phone has been unlocked and looked at, short
     * enough that a tile is not still claiming success the next morning — a widget
     * cannot animate, so this is the only thing standing between "Done" and "Done,
     * permanently".
     */
    const val SETTLE_MS = 3_000L
}
