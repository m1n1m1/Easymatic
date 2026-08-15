package com.example.ottomatic.feature.ai

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.service.CallableMacro
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.ToolSpec
import com.example.ottomatic.domain.model.ToolTarget
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.domain.registry.ConfigSchemaRegistry
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.PickerOptions
import com.example.ottomatic.engine.ai.canRunAsTool

/**
 * One thing a model profile could be allowed to do, and whether it is.
 *
 * The **whole candidate set is offered**, ticked or not, which is what makes "allow
 * everything" and "allow nothing" one gesture each. That is the opposite of the list
 * this replaced, where a tool existed only once it had been added — and where the
 * question "what else could I allow?" had no answer on screen at all.
 */
internal data class ToolCandidate(
    val target: ToolTarget,
    val title: String,
    val spec: ToolSpec?,
    /**
     * What the *profile* says about this row, when a node is adjusting one.
     *
     * `null` while the profile itself is being edited — there is nothing behind it
     * then, and [isOverridden] is correspondingly always false.
     */
    val base: ToolSpec? = null,
    private val hasBaseline: Boolean = false,
) {
    val allowed: Boolean get() = spec != null

    /** Whether this node has departed from its profile here, in either direction. */
    val isOverridden: Boolean get() = hasBaseline && spec != base

    /**
     * Whether this is ticked but still needs a visit before it will do anything.
     *
     * A picker **nothing can enumerate** holds an identifier a model cannot invent, so
     * leaving it open would run the tool against a default that names nothing — the
     * exact failure `@Picker` exists to prevent, one level out. It is a badge rather
     * than a refusal because "allow everything" is meant to be one tap: the row says
     * what is left to do rather than the switch refusing to move.
     *
     * A picker that *can* be enumerated is not counted, and that is the change the
     * whole feature turns on: leaving the scene open is now an answer rather than an
     * omission, because the model is handed the list to choose from.
     */
    val needsChoice: Boolean get() = spec != null && loosePickers(target, spec).isNotEmpty()
}

/** One heading and its rows. */
internal data class ToolGroup(
    val title: String,
    val candidates: List<ToolCandidate>,
) {
    val allowedCount: Int get() = candidates.count { it.allowed }

    /** Three states, because a group half-ticked is neither on nor off. */
    val state: GroupState
        get() = when (allowedCount) {
            0 -> GroupState.NONE
            candidates.size -> GroupState.ALL
            else -> GroupState.SOME
        }
}

internal enum class GroupState { NONE, SOME, ALL }

/**
 * The picker fields of [target] that this spec has neither answered nor can leave to
 * the model.
 *
 * **Only the ones nothing can enumerate**, which is the change the feature turns on: a
 * light scene left open is now a real answer — the model is handed the list of scenes
 * — where a sound URI left open is still an omission, because nothing can offer the
 * set and the tool would run against a default naming nothing.
 *
 * A macro tool has none by construction — its parameters come from a `@Ports` spec
 * that names types rather than identifiers — so only a node is asked.
 */
internal fun loosePickers(target: ToolTarget, spec: ToolSpec): List<String> {
    val typeId = (target as? ToolTarget.Node)?.typeId ?: return emptyList()
    return ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty()
        .filter { field ->
            val type = field.type as? ConfigFieldType.PICKER ?: return@filter false
            field.key !in spec.pinned && !canBeChosenByModel(type, spec)
        }
        .map { it.label }
}

/**
 * Whether the model could be handed this picker's answer set instead of the author
 * pinning it.
 *
 * The scope is the spec's own pinned map, so pinning the hub narrows what the field
 * beneath it offers, exactly as `NodeToolCatalog` resolves it on the run path — the
 * two must agree, or the form would promise a choice the catalogue then withholds.
 */
internal fun canBeChosenByModel(type: ConfigFieldType.PICKER, spec: ToolSpec): Boolean =
    PickerOptions.of(type.kind, type.scopedBy.map { spec.pinned[ConfigKey(it)].orEmpty() }).isNotEmpty()

/**
 * Every node and macro a profile could be granted, grouped the way the palette groups
 * them.
 *
 * **Grouped by [NodeCategory] rather than listed flat**, because seventy-odd rows with
 * one switch above them is a wall: the categories are the vocabulary the user already
 * met in the palette, and a per-group switch is how "let it read things but not send
 * anything" is expressed in one tap rather than twenty.
 *
 * The candidate set is `canRunAsTool` — the same filter the node palette was
 * restricted to when tools were picked one at a time — so a loop, a trigger or a
 * graph-shaped action never appears here to be ticked and silently do nothing.
 */
@Suppress("LongParameterList") // Two lists and three renderers; a holder would only rename them.
internal fun toolGroups(
    allowed: List<ToolSpec>,
    macros: List<CallableMacro>,
    categoryTitle: (NodeCategory) -> String,
    macroGroupTitle: String,
    nodeTitle: (NodeTypeId) -> String,
    baseline: List<ToolSpec>? = null,
): List<ToolGroup> {
    val byTarget = allowed.associateBy { it.target }
    val baseByTarget = baseline?.associateBy { it.target }
    fun candidate(target: ToolTarget, title: String) = ToolCandidate(
        target = target,
        title = title,
        spec = byTarget[target],
        base = baseByTarget?.get(target),
        hasBaseline = baseline != null,
    )

    val nodeGroups = NodeTypeRegistry.all
        .filter { canRunAsTool(it.typeId, it.kind) }
        .groupBy { it.category }
        .map { (category, definitions) ->
            ToolGroup(
                title = categoryTitle(category),
                candidates = definitions.map { definition ->
                    candidate(ToolTarget.Node(definition.typeId), nodeTitle(definition.typeId))
                },
            )
        }
    // Macros last, because they are the tier that varies per phone — a heading whose
    // contents change between two installs belongs after the ones that do not.
    val macroGroup = macros.takeIf { it.isNotEmpty() }?.let { callable ->
        ToolGroup(
            title = macroGroupTitle,
            candidates = callable.map { macro -> candidate(ToolTarget.Macro(macro.id), macro.name) },
        )
    }
    return nodeGroups + listOfNotNull(macroGroup)
}

/**
 * [allowed] with [targets] ticked or unticked, preserving what is already pinned.
 *
 * **Un-ticking forgets the pins**, and that is the honest behaviour rather than a
 * missing feature: a tool nobody is allowed has no configuration, and silently keeping
 * a hidden one would make re-ticking a row restore choices the user cannot see. Only
 * ticking is additive.
 */
internal fun List<ToolSpec>.setAllowed(targets: Collection<ToolTarget>, allow: Boolean): List<ToolSpec> {
    val wanted = targets.toSet()
    val kept = filterNot { it.target in wanted }
    if (!allow) return kept
    val existing = associateBy { it.target }
    return kept + wanted.map { target -> existing[target] ?: ToolSpec(target) }
}

/**
 * [allowed] with [targets] put back to what [baseline] says about them.
 *
 * **Reset is not the same as unticking**, and conflating the two is the mistake this
 * function exists to prevent: unticking a row the profile grants writes a *removal*,
 * where resetting it drops the node's line entirely so the row follows the profile
 * again — including when the profile changes later. On the writing side the difference
 * is invisible (both produce the same effective list *today*) and on the next profile
 * edit it is the whole behaviour.
 */
internal fun List<ToolSpec>.resetTo(baseline: List<ToolSpec>, targets: Collection<ToolTarget>): List<ToolSpec> {
    val wanted = targets.toSet()
    val restored = baseline.filter { it.target in wanted }
    return filterNot { it.target in wanted } + restored
}

/**
 * Whether [allowed] plus [adding] more would exceed what a prompt can carry.
 *
 * Asked before a switch moves rather than after, so the ceiling is a sentence the user
 * reads instead of a truncation they never see — see `ToolSpec.MAX_TOOLS`.
 */
internal fun wouldExceedCap(allowed: Int, adding: Int): Boolean = allowed + adding > ToolSpec.MAX_TOOLS
