package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.ServiceCall
import com.example.ottomatic.domain.model.HomeAssistantRef
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Hint
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
    @Label("Home Assistant")
    @Picker(PickerKind.HA_HUB, optional = true)
    val hub: String = "",
    /**
     * Still called `target` rather than the `entity` its label says.
     *
     * A config key is **persisted**, and `NodeSchema.decode` reads with
     * `ignoreUnknownKeys` — so renaming this would leave every saved node's entity in a key
     * nothing reads, the property would silently take its default, and `light.turn_on` would
     * fire at nothing and report success doing it. The label is not persisted and can say
     * whatever is clearest.
     */
    @Label("Entity")
    @Picker(PickerKind.HA_ENTITY, scopedBy = ["hub", "service"], optional = true)
    val target: String = "",
    @Label("Service")
    @Picker(PickerKind.HA_SERVICE, scopedBy = ["hub", "target"])
    val service: String = "",
    @Label("Extra data")
    @Hint("JSON")
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
 * **Hub → Entity → Service, each scoping the next**, and this is a recorded reversal rather
 * than the original design. It used to read: *the hub comes from the service reference, two
 * independent pickers could name a service on hub A and an entity from hub B, so the service is
 * authoritative and a mismatch is reported at run time rather than made unrepresentable.* That
 * was the weaker guarantee, taken knowingly, because the alternative was threading a sibling
 * value through `PickerField` "for one caller".
 *
 * Both halves of that expired at once. There are now several callers, so the cost argument is
 * gone — and scoping turns out to be the *cure* for the incoherent state rather than a second
 * way of causing it: choosing a hub is what makes another hub's entities unofferable, and
 * choosing an entity is what makes a service its domain rejects unofferable. The state the old
 * paragraph accepted and reported is now unrepresentable through the form, which is what
 * `SmartHomeRef`'s argument wanted all along and could not give when there were two unrelated
 * fields.
 *
 * The run-time mismatch checks below therefore stay, but they are now guards on **hand-edited
 * or imported JSON** rather than on anything the editor can produce. And `service.hubId`
 * remains authoritative at run time: the `hub` field *scopes the form* and is never the
 * authority on which server a reference belongs to, which is `SmartHomeRef`'s rule preserved
 * rather than broken. That is also what makes an old macro work untouched — see below.
 *
 * **Blank target is legal**, unlike most `@Picker` fields, and that is not an oversight:
 * `homeassistant.restart`, `script.turn_on` with the script named in the service itself, and a
 * scene recall all take no entity. Refusing blank would make those unreachable — and it is now
 * declared with `optional = true`, because until it was, the Problems panel badged every one of
 * those nodes for having nothing chosen.
 *
 * **An old macro needs no migration, and the declaration is what does it.** A node saved before
 * the `hub` field existed has only `service` and `target`, and the service reference has always
 * carried its hub inside it — so `scopedBy = ["hub", "service"]` finds the hub in the *absence*
 * of the first and the presence of the second, and both pickers open correctly scoped with
 * nothing touched. See `haScopeOf`, which takes the first hub it finds for exactly this reason.
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
