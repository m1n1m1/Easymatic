package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.domain.model.AiModelProfile
import com.example.ottomatic.domain.model.Workflow

/**
 * Rewrites a workflow saved before AI model profiles existed, so its nodes point at
 * one.
 *
 * A legacy AI node held **two** fields — a connection id and a `FAST`/`BALANCED`/
 * `THOROUGH` tier — where a node now holds one profile id. Because
 * `AiConnectionRepository` mints its migrated profiles with **derived** ids
 * ([AiModelProfile.legacyId]), the new value is a pure function of the two old ones:
 * this needs no library lookup, no suspension point and no ordering against the
 * library's own migration, which runs on a different thread. That is the one
 * awkwardness [repairVariableRefs] has and this does not.
 *
 * It also retires **`action.ai_agent`** into `action.ai_prompt`. That node existed for
 * one release cycle as the tool-using half of a pair, and the pair collapsed when the
 * tools moved onto the profile and a plain switch replaced the second card. A typeId
 * nothing declares is a node discarded on load, so the rewrite happens here rather
 * than being left to strand a macro.
 *
 * **What cannot be carried across is the retired node's own tool list**, and that is
 * stated rather than left to be discovered: those permissions now belong to a
 * *profile*, and nothing here can know which of the user's profiles was meant. The
 * node keeps working — it becomes an Ask AI with tools switched on — and the
 * permissions are chosen once, in the connection library, where they now live.
 *
 * **Idempotent.** A node that already carries a `modelRef` is left exactly as it is,
 * so running this on every load costs nothing after the first.
 */
fun repairAiRefs(workflow: Workflow): Workflow {
    val nodes = workflow.nodes.map { node ->
        val legacyAgent = node.typeId == LEGACY_AGENT
        if (!legacyAgent && node.typeId !in AI_NODES) return@map node
        if (node.config[MODEL_REF]?.isNotBlank() == true && !legacyAgent) return@map node

        val repaired = node.config.toMutableMap()
        repaired[MODEL_REF] = repaired[MODEL_REF]?.takeIf { it.isNotBlank() }
            ?: profileRefFor(repaired[LEGACY_CONNECTION].orEmpty(), repaired[LEGACY_TIER].orEmpty())
        if (legacyAgent) repaired[USE_TOOLS] = "true"
        repaired.remove(LEGACY_CONNECTION)
        repaired.remove(LEGACY_TIER)
        repaired.remove(LEGACY_TOOLS)

        node.copy(
            typeId = if (legacyAgent) PROMPT else node.typeId,
            config = repaired,
        )
    }
    return if (nodes == workflow.nodes) workflow else workflow.copy(nodes = nodes)
}

/**
 * The profile a legacy (connection, tier) pair became.
 *
 * A blank connection stays blank rather than becoming a reference to nothing: that is
 * a node with nothing chosen, which the validator already has a sentence for. An
 * unrecognised tier name falls back to `FAST`, which is what the old config's own
 * default was.
 */
private fun profileRefFor(connectionId: String, tier: String): String {
    if (connectionId.isBlank()) return ""
    val effort = AiModel.entries.firstOrNull { it.name == tier } ?: AiModel.FAST
    return AiModelProfile.legacyId(connectionId, effort)
}

private val PROMPT = NodeTypeId("action.ai_prompt")

private val LEGACY_AGENT = NodeTypeId("action.ai_agent")

/** Every node that named a connection and a tier. Listed rather than derived, because
 *  the fields they are being repaired *from* no longer exist to be derived from. */
private val AI_NODES = setOf(PROMPT, NodeTypeId("action.ai_describe"))

private val MODEL_REF = ConfigKey("modelRef")

private val USE_TOOLS = ConfigKey("useTools")

private val LEGACY_CONNECTION = ConfigKey("connectionId")

private val LEGACY_TIER = ConfigKey("model")

private val LEGACY_TOOLS = ConfigKey("tools")
