package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.nodeapi.plugin.PluginChannel
import com.example.ottomatic.nodeapi.wire.NodeDeclarationWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One node contributed by an enabled plugin, with the way to reach it.
 *
 * [channel] travels with the entry rather than being looked up when a node runs,
 * because the four registries are `object`s with nothing injected into them and the
 * executor reaches them from paths that can neither suspend nor be given a dependency.
 * It is a pure-Kotlin interface (see [PluginChannel]), so holding one here does not
 * put Android into `domain`.
 */
data class PluginNodeEntry(
    val packageName: String,
    val pluginName: String,
    val definition: NodeTypeDefinition,
    val configSchema: NodeConfigSchema?,
    val declaration: NodeDeclarationWire,
    val channel: PluginChannel,
    /**
     * Permissions this node needs that **the plugin's own package** has not been
     * granted.
     *
     * Computed in `data/` at refresh time with
     * `PackageManager.checkPermission(perm, pluginPackage)` — against the plugin,
     * never against Ottomatic — and rather than through a hydrated checker, because
     * the answer is already in hand at the one moment the entry is built.
     *
     * `GraphValidator` raises a WARNING that blocks nothing, on `validatePrerequisites`'
     * reasoning: this is a fact about the phone rather than about the wiring, and
     * flipping a switch in Settings starts it working with no edit to the graph. It
     * deliberately does **not** enter `PermissionCatalogue` or `GrantedPrerequisites`,
     * which answer "what does *this app* need" — an answer that must not change because
     * somebody installed a plugin.
     */
    val missingPermissions: List<String> = emptyList(),
    /**
     * Why the plugin says it cannot currently do its work, or null when it says nothing.
     *
     * The gap [missingPermissions] cannot reach, and the reason it needs a second field
     * rather than a longer first one: a plugin holds every permission it asked for, so
     * that list is empty and correct while every node of a signed-out plugin does
     * nothing. "Configured perfectly, does nothing", with nothing anywhere saying why.
     *
     * The sentence is the plugin's own and untranslated, exactly as its node names and
     * descriptions are — the declaration crosses the binder pre-rendered, so there is no
     * resource key to look up.
     *
     * **Null is "say nothing", not "ready".** A plugin that could not be reached, timed
     * out or answered nonsense leaves this null, because the only consumer is a warning
     * and a panel that badges every node of a plugin it merely failed to ask is worse
     * than one that waits until it knows — `GrantedPrerequisites`' own inversion.
     */
    val notReady: String? = null,
    /**
     * The class name of the plugin's own chooser Activity, or null when it exports none.
     *
     * What a `@PluginChoice(chooser = SCREEN)` field opens. A **class name and not a
     * `ComponentName`** because this is `domain/`, which holds nothing of Android's;
     * [packageName] is right here, so `feature/` puts the two together.
     *
     * Resolved from `PackageManager` at refresh time, never read off the wire. A component
     * name is a thing to *launch*, so it is the one piece of a plugin's chooser that could
     * not have travelled with the declaration — see `PluginPackages.chooserActivityOf`.
     */
    val chooserActivity: String? = null,
)

/**
 * The nodes contributed by whichever plugins are installed *and* enabled right now.
 *
 * The fifth hydrated registry, after [GlobalVariables], [SmartHomeHubs],
 * [AiConnections] and [GrantedPrerequisites], and it works the way those four do for
 * the reason they do: `domain` has to answer questions about things only `data` can
 * discover, from paths — `effectivePorts`, `GraphValidator`, the palette — that can
 * neither suspend nor be injected into.
 *
 * It is the first of the five whose *contents* a screen draws, though, so it is the
 * first that has to be observable. [entries] is a `StateFlow` rather than a plain
 * snapshot; a `MutableStateFlow` rather than Compose's `mutableStateOf`, because
 * `domain` may not depend on Compose and `collectAsState` in `feature/` is where that
 * dependency belongs.
 *
 * ## Unhydrated is not empty
 *
 * [isHydrated] is false until the first discovery pass finishes, which is
 * asynchronous and lands some way after process start. That distinction is
 * load-bearing rather than pedantic: `GraphValidator` must not report a plugin node in
 * a saved macro as an unknown type merely because the answer has not arrived yet, or
 * every boot-time snapshot validation would condemn every plugin node in every macro.
 * That is precisely the mistake [MacroDirectory]'s KDoc exists to warn about.
 */
object PluginNodes {

    private val state = MutableStateFlow<List<PluginNodeEntry>?>(null)

    /** Null until the first discovery pass finishes. */
    val entries: StateFlow<List<PluginNodeEntry>?> = state

    /** False until the first discovery pass finishes; see the class KDoc. */
    val isHydrated: Boolean get() = state.value != null

    fun all(): List<PluginNodeEntry> = state.value.orEmpty()

    fun byId(typeId: NodeTypeId): PluginNodeEntry? =
        state.value?.firstOrNull { it.definition.typeId == typeId }

    fun hydrate(entries: List<PluginNodeEntry>) {
        state.value = entries
    }

    /** Test seam: puts the registry back to never-having-answered. */
    fun reset() {
        state.value = null
    }
}
