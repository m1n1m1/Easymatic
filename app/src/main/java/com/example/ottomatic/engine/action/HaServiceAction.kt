package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.ServiceCall
import com.example.ottomatic.domain.model.HomeAssistantRef
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.HaServiceCalled
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

@kotlinx.serialization.Serializable
data class HaServiceConfig(
    @Label("Service")
    @Picker(PickerKind.HA_SERVICE)
    val service: String = "",
    @Label("Entity")
    @Picker(PickerKind.HA_ENTITY)
    val target: String = "",
    @Label("Extra data (JSON)")
    @Multiline
    @Wired
    val data: String = "",
)

/**
 * Runs any Home Assistant service.
 *
 * The escape hatch beside the three light nodes, and the reason the integration is not
 * only about lights: a thermostat, a vacuum, a media player, a lock, a script and a
 * scene of somebody's own are all one service call away, and none of them fits
 * `LightCommand`. Adding a node per device class would be a node per device class for
 * ever; this is one node and a chooser over whatever that server actually offers.
 *
 * **The hub comes from the service reference, and the entity does not carry one of its
 * own.** Two independent pickers could name a service on hub A and an entity from hub B,
 * which is the incoherent state `SmartHomeRef`'s KDoc argues against — but the fix there
 * (fold the hub into the one reference) cannot apply twice, because there are genuinely
 * two things to choose. So the service is authoritative and a mismatch is **reported at
 * run time** rather than made unrepresentable. That is the weaker guarantee, and it is
 * taken knowingly: the alternative is threading a sibling value through `PickerField` for
 * one caller, which CLAUDE.md already declines, and on the overwhelmingly common
 * one-instance install the mismatch cannot arise at all.
 *
 * **Blank target is legal**, unlike every `@Picker` field in the app, and that is not an
 * oversight: `homeassistant.restart`, `script.turn_on` with the script named in the
 * service itself, and a scene recall all take no entity. Refusing blank would make those
 * unreachable.
 *
 * **Failure lands on the port and `out` still pulses**, which is `action.ai_prompt`'s and
 * `transform.json_read`'s contract: a service that will not run is a thing the macro can
 * decide about, not a reason to stop the graph. Malformed JSON in the data field is
 * refused **before** the network, because sent through it comes back as a generic 400
 * with nothing in it about the box the user mistyped.
 */
class HaServiceAction : Action<HaServiceConfig, HaServiceCalled> {

    override val definition = actionNode<HaServiceConfig, HaServiceCalled>(
        typeId = "action.ha_service",
        displayName = "Call Home Assistant Service",
        description =
            "Runs any Home Assistant service — a thermostat, a media player, a lock, a vacuum, a script",
        category = NodeCategory.SMART_HOME,
        icon = NodeIcon.HOME,
        output = dataOut<HaServiceCalled>("state"),
    )

    override suspend fun execute(
        input: HaServiceConfig,
        context: ExecutionContext,
    ): NodeOutput<HaServiceCalled> {
        val service = HomeAssistantRef.parse(input.service)
        val target = HomeAssistantRef.parse(input.target)
        val problem = when {
            service == null && input.service.isBlank() -> "No service chosen"
            service == null -> "Not a Home Assistant service: \"${input.service.trim()}\""
            service.domain.isBlank() || service.service.isBlank() ->
                "Not a service name: \"${service.id}\" — one looks like \"light.turn_on\""
            // The honest report of the state two pickers make representable. Naming both
            // hubs is the whole diagnosis, so both are named.
            target != null && target.hubId != service.hubId ->
                "That entity is on a different Home Assistant than that service"
            else -> null
        }
        if (service == null || problem != null) {
            context.log(problem.orEmpty(), LogLevel.ERROR)
            return NodeOutput(
                HaServiceCalled(
                    service = input.service,
                    target = input.target,
                    called = false,
                    error = problem.orEmpty(),
                ),
            )
        }

        val result = context.homeAssistant.call(
            ServiceCall(
                hubId = service.hubId,
                domain = service.domain,
                service = service.service,
                targetEntityId = target?.id.orEmpty(),
                data = input.data,
            ),
        )
        if (!result.called) context.log(result.error, LogLevel.WARN)
        return NodeOutput(
            HaServiceCalled(
                service = service.id,
                target = target?.id.orEmpty(),
                called = result.called,
                response = result.response,
                error = result.error,
            ),
        )
    }
}
