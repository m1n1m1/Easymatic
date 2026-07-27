package com.example.ottomatic.engine.value

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ValueNode
import com.example.ottomatic.engine.valueNode

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
