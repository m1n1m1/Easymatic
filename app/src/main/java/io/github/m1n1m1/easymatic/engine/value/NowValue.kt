package io.github.m1n1m1.easymatic.engine.value

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.ValueNode
import io.github.m1n1m1.easymatic.engine.valueNode

/**
 * `value.now` — the current date and time.
 *
 * The clock reader every other date node is measured against. Being a value node it
 * is *pulled* rather than pulsed, and it can be named directly as an `action.if`
 * source with no edge and no execution position — so "only after 18:00" is one
 * comparison node on the canvas rather than a trigger plumbed into arithmetic.
 *
 * It reads a clock rather than a device subsystem, so it implements [ValueNode]
 * directly instead of extending [DeviceValue]: there is nothing that can fail and
 * therefore nothing to report as unreadable.
 */
class NowValue : ValueNode<NoConfig, DateTime> {

    override val definition = valueNode<NoConfig, DateTime>(
        typeId = "value.now",
        displayName = "Current time",
        description = "The current date and time",
        category = NodeCategory.VALUE_TIME,
        icon = NodeIcon.SCHEDULE,
        output = dataOut("now", label = "Now"),
    )

    override suspend fun read(config: NoConfig, context: ExecutionContext): DateTime = DateTime.now()
}
