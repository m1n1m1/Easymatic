package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId

/**
 * Registry of per-node-type configuration schemas.
 * A node with no entry here has no configurable fields.
 *
 * This object holds no declarations of its own: the schemas are derived from the
 * config classes of the single node definitions registered in [ActionRegistry],
 * [TriggerRegistry], [ValueRegistry] and [TransformRegistry]. `action.if` narrows
 * its derived schema further at design time (see [effectiveConfigSchema]).
 *
 * The *types* this serves — [ConfigFieldType], [ConfigField], [ConfigOption],
 * [VisibilityRule], [NodeConfigSchema] — live in `:node-api` beside [NodeSchema],
 * which is what derives them. They are part of the declaration surface a plugin
 * compiles against; this registry, which walks the app's own compiled node lists,
 * is not.
 *
 * Like [NodeTypeRegistry], it now derives from two sources: those compiled lists, and
 * whichever plugins are enabled. A plugin's schema is derived by [NodeSchema] too —
 * on the plugin's side of the boundary, from the plugin's own config class — so a form
 * row here edits a value that node really reads.
 */
object ConfigSchemaRegistry {

    private val byId: Map<NodeTypeId, NodeConfigSchema> =
        (
            ActionRegistry.all().map { it.definition.configSchema } +
                TriggerRegistry.all().map { it.definition.configSchema } +
                ValueRegistry.all().map { it.definition.configSchema } +
                TransformRegistry.all().map { it.definition.configSchema }
            )
            .filterNotNull()
            .associateBy { it.typeId }

    fun byId(typeId: NodeTypeId): NodeConfigSchema? = byId[typeId] ?: PluginNodes.byId(typeId)?.configSchema
}
