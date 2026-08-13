package com.example.ottomatic.feature.widget

import com.example.ottomatic.ServiceLocator
import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.domain.model.MacroAccent
import com.example.ottomatic.domain.model.MacroIcon
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.engine.trigger.ManualTrigger
import com.example.ottomatic.engine.validation.GraphValidator
import com.example.ottomatic.feature.i18n.NodeText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One manual trigger, as a widget needs to know it.
 *
 * The [workflowId] travels with the [nodeId] because a node id is only unique
 * inside its own graph, and a widget holds a reference across every graph on the
 * device. Everything else here is what a tile draws, resolved once so a tile never
 * has to reach back for it.
 */
data class ManualTriggerRef(
    val workflowId: String,
    val nodeId: String,
    val macroName: String,
    val label: String,
    val icon: MacroIcon,
    val accent: MacroAccent,
    val enabled: Boolean,
) {
    /** The stable key a widget stores and [RunFeedback] reports against. */
    val key: String get() = "$workflowId:$nodeId"
}

/** A macro, as the status widget counts it. */
data class MacroSnapshot(
    val workflowId: String,
    val name: String,
    val enabled: Boolean,
    val errorCount: Int,
    val triggers: List<ManualTriggerRef>,
)

/**
 * Everything the three widgets render, built from the stored workflows.
 *
 * **Cached, and invalidated only by a repository change.** Building it loads every
 * workflow and validates each graph, which is the same work
 * `WorkflowListViewModel.refresh()` already does on every `ON_RESUME` — acceptable
 * once, wasteful on every widget update, and widgets update on every finished run.
 * The cache is what makes "redraw the tiles because a macro finished" cost nothing.
 *
 * Deliberately **not** a second index file on disk. That would be a second source
 * of truth to keep in step with the workflow files for a workload the app already
 * performs synchronously; if a user with many macros ever makes this visibly slow,
 * an index is the answer then, not before.
 *
 * Lives in `feature/` because it calls [GraphValidator] from `engine/validation/`,
 * and `data ← domain + core` forbids a repository-side helper from doing that.
 */
object MacroSnapshots {

    private val mutex = Mutex()

    @Volatile
    private var cached: List<MacroSnapshot>? = null

    /** Drops the cache. Called by `WidgetUpdater` when the repository reports a write. */
    fun invalidate() {
        cached = null
    }

    /**
     * Every macro with its manual triggers, ordered by macro name.
     *
     * The [mutex] is not about the cache being wrong without it — a redundant
     * rebuild would be harmless — but about the cost: three widget classes update
     * on the same signal, and unguarded they would each load and validate every
     * graph on the device at the same moment.
     */
    suspend fun all(): List<MacroSnapshot> {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: build().also { cached = it }
        }
    }

    /** Every manual trigger on the device, flattened, in the order [all] gives them. */
    suspend fun triggers(): List<ManualTriggerRef> = all().flatMap { it.triggers }

    /** The trigger [key] names, or null once the macro or the node is gone. */
    suspend fun trigger(key: String): ManualTriggerRef? = triggers().firstOrNull { it.key == key }

    private suspend fun build(): List<MacroSnapshot> {
        val repository = ServiceLocator.workflowRepository
        return repository.list().mapNotNull { summary ->
            val workflow = repository.load(summary.id) ?: return@mapNotNull null
            MacroSnapshot(
                workflowId = workflow.id,
                name = workflow.name,
                enabled = workflow.enabled,
                errorCount = GraphValidator(workflow).validate().errors.size,
                triggers = workflow.manualTriggers(),
            )
        }
    }
}

/**
 * This workflow's `trigger.manual` nodes, in graph order.
 *
 * The label falls back twice, and the order is what makes a deck readable. A
 * button's own label wins because it is the only one the user wrote *for the
 * button*; the node's name comes next, since renaming a node on the canvas is the
 * other way people say what a step is; and the macro's name is last, because a
 * macro with exactly one manual trigger — much the commonest case — should show
 * its own name on the home screen rather than the word "Manual Trigger".
 */
internal fun Workflow.manualTriggers(): List<ManualTriggerRef> =
    nodes.filter { it.typeId == ManualTrigger.TYPE_ID }.map { node ->
        val label = node.config[ConfigKey(ManualTrigger.LABEL_KEY)]?.takeIf { it.isNotBlank() }
            ?: node.name.takeIf { it.isNotBlank() && it !in untouchedNodeNames }
            ?: name
        ManualTriggerRef(
            workflowId = id,
            nodeId = node.id.value,
            macroName = name,
            label = label,
            icon = icon,
            accent = accent,
            enabled = enabled,
        )
    }

/**
 * The names the palette gives a freshly-dropped `trigger.manual` —
 * `GraphEditorViewModel.addNode` copies the definition's display name into it.
 *
 * Treated as "unnamed" rather than as a label, because it is: every manual trigger
 * on the device carries it until someone renames one, and a deck of eight cells all
 * reading "Manual Trigger" is exactly the failure the fallback chain exists to
 * avoid. Read from the registry rather than written out here, so renaming the node
 * type cannot leave this comparing against a string nothing uses any more.
 *
 * **Two names, not one**, and that is what makes this survive a locale change. The
 * name is translated at placement and then persisted, so a macro built before the
 * phone's language changed carries the *old* language's default while a macro built
 * after carries the new one. Matching only the current translation would promote
 * every older default to a user-chosen label, putting "Manual Trigger" back on the
 * home screen — the exact failure this guards against.
 */
private val untouchedNodeNames: Set<String>
    get() {
        val definition = NodeTypeRegistry.byId(ManualTrigger.TYPE_ID) ?: return emptySet()
        val translated = ServiceLocator.appContextOrNull
            ?.let { NodeText.of(it.resources).name(definition) }
        return setOfNotNull(definition.displayName, translated)
    }
