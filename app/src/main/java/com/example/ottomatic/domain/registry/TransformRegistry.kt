package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.engine.ExecutableTransform
import com.example.ottomatic.engine.transform.BuildTextTransform
import com.example.ottomatic.engine.transform.ConvertTransform
import com.example.ottomatic.engine.transform.JsonReadTransform

/**
 * Central registry mapping a transform [typeId] to its implementation.
 *
 * This list is the *only* registration step for a new
 * [com.example.ottomatic.engine.ExecutableTransform], exactly as [ActionRegistry]
 * is for actions and [ValueRegistry] for values: the transform's own file declares
 * its metadata, ports and config in a single
 * [com.example.ottomatic.engine.TransformNodeDefinition]. [NodeTypeRegistry] and
 * [ConfigSchemaRegistry] derive their views from these declarations.
 *
 * Like [ValueRegistry] there is no execution bridge — a transform is never pulsed.
 * The executor pulls it when a consumer needs its output, and pulling it pulls
 * whatever it depends on in turn.
 */
object TransformRegistry {

    private val transforms: List<ExecutableTransform> = listOf(
        BuildTextTransform(),
        ConvertTransform(),
        JsonReadTransform(),
    )

    private val byId: Map<NodeTypeId, ExecutableTransform> = transforms.associateBy { it.typeId }

    fun byId(typeId: NodeTypeId): ExecutableTransform? = byId[typeId]

    fun all(): List<ExecutableTransform> = transforms
}
