package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.engine.ExecutableTransform
import io.github.m1n1m1.easymatic.engine.transform.BuildTextTransform
import io.github.m1n1m1.easymatic.engine.transform.ConvertTransform
import io.github.m1n1m1.easymatic.engine.transform.JsonReadTransform
import io.github.m1n1m1.easymatic.engine.transform.ListContainsTransform
import io.github.m1n1m1.easymatic.engine.transform.ListCountTransform
import io.github.m1n1m1.easymatic.engine.transform.ListIndexOfTransform
import io.github.m1n1m1.easymatic.engine.transform.ListItemTransform
import io.github.m1n1m1.easymatic.engine.transform.ListJoinTransform
import io.github.m1n1m1.easymatic.engine.transform.ListSliceTransform
import io.github.m1n1m1.easymatic.engine.transform.ListSortTransform
import io.github.m1n1m1.easymatic.engine.transform.SplitTextTransform

/**
 * Central registry mapping a transform [typeId] to its implementation.
 *
 * This list is the *only* registration step for a new
 * [io.github.m1n1m1.easymatic.engine.ExecutableTransform], exactly as [ActionRegistry]
 * is for actions and [ValueRegistry] for values: the transform's own file declares
 * its metadata, ports and config in a single
 * [io.github.m1n1m1.easymatic.engine.TransformNodeDefinition]. [NodeTypeRegistry] and
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
        SplitTextTransform(),
        ListCountTransform(),
        ListItemTransform(),
        ListJoinTransform(),
        ListContainsTransform(),
        ListIndexOfTransform(),
        ListSortTransform(),
        ListSliceTransform(),
    )

    private val byId: Map<NodeTypeId, ExecutableTransform> = transforms.associateBy { it.typeId }

    fun byId(typeId: NodeTypeId): ExecutableTransform? = byId[typeId]

    fun all(): List<ExecutableTransform> = transforms
}
