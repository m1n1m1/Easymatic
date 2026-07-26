package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.engine.ExecutableAction
import com.example.ottomatic.engine.action.AutoRotateAction
import com.example.ottomatic.engine.action.BluetoothAction
import com.example.ottomatic.engine.action.BreakStructAction
import com.example.ottomatic.engine.action.BrightnessAction
import com.example.ottomatic.engine.action.CallAction
import com.example.ottomatic.engine.action.ClipboardAction
import com.example.ottomatic.engine.action.DelayAction
import com.example.ottomatic.engine.action.DisableMacroAction
import com.example.ottomatic.engine.action.DndAction
import com.example.ottomatic.engine.action.EnableMacroAction
import com.example.ottomatic.engine.action.FlashlightAction
import com.example.ottomatic.engine.action.HttpAction
import com.example.ottomatic.engine.action.IfAction
import com.example.ottomatic.engine.action.LaunchAppAction
import com.example.ottomatic.engine.action.LogAction
import com.example.ottomatic.engine.action.NotifyAction
import com.example.ottomatic.engine.action.OpenUrlAction
import com.example.ottomatic.engine.action.RingerModeAction
import com.example.ottomatic.engine.action.ScreenTimeoutAction
import com.example.ottomatic.engine.action.SendSmsAction
import com.example.ottomatic.engine.action.StopAction
import com.example.ottomatic.engine.action.VibrateAction
import com.example.ottomatic.engine.action.VolumeAction
import com.example.ottomatic.engine.action.WifiAction

/**
 * Central registry mapping an action [typeId] to its executable implementation.
 *
 * This list is the *only* registration step for a new [Action]: the action's
 * own file declares everything else (metadata, ports, config fields and
 * contract) in its single [com.example.ottomatic.engine.ActionNodeDefinition].
 * [NodeTypeRegistry] and [ConfigSchemaRegistry] derive their views from these
 * definitions. The single adaptive [BreakStructAction] splits any
 * `@Serializable` struct into its fields at runtime; per-field data outputs
 * are exposed directly on each node via
 * [com.example.ottomatic.domain.registry.effectivePorts] (no dedicated
 * make-struct action is needed).
 *
 * The other adaptive node is [IfAction], the graph's single comparison and only
 * conditional branch. It is an ordinary action registered here like any other —
 * there is no separate condition registry, because a condition is not a node family
 * but a comparison over a value.
 */
object ActionRegistry {

    private val actions: List<ExecutableAction> = listOf(
        AutoRotateAction(),
        BluetoothAction(),
        BrightnessAction(),
        CallAction(),
        ClipboardAction(),
        DelayAction(),
        DisableMacroAction(),
        DndAction(),
        EnableMacroAction(),
        FlashlightAction(),
        HttpAction(),
        IfAction(),
        LaunchAppAction(),
        LogAction(),
        NotifyAction(),
        OpenUrlAction(),
        RingerModeAction(),
        ScreenTimeoutAction(),
        SendSmsAction(),
        StopAction(),
        VibrateAction(),
        VolumeAction(),
        WifiAction(),
        BreakStructAction(),
    )

    private val byId: Map<NodeTypeId, ExecutableAction> = actions.associateBy { it.typeId }

    fun byId(typeId: NodeTypeId): ExecutableAction? = byId[typeId]

    fun all(): List<ExecutableAction> = actions
}
