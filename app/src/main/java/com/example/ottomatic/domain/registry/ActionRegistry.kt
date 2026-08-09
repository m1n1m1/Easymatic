package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.engine.ExecutableAction
import com.example.ottomatic.engine.action.AskChoiceAction
import com.example.ottomatic.engine.action.AskConfirmAction
import com.example.ottomatic.engine.action.AskInputAction
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
import com.example.ottomatic.engine.action.ForEachAction
import com.example.ottomatic.engine.action.HttpAction
import com.example.ottomatic.engine.action.IfAction
import com.example.ottomatic.engine.action.LaunchAppAction
import com.example.ottomatic.engine.action.ListAddAction
import com.example.ottomatic.engine.action.ListClearAction
import com.example.ottomatic.engine.action.LogAction
import com.example.ottomatic.engine.action.NotifyAction
import com.example.ottomatic.engine.action.OpenUrlAction
import com.example.ottomatic.engine.action.PlaySoundAction
import com.example.ottomatic.engine.action.RepeatAction
import com.example.ottomatic.engine.action.RingerModeAction
import com.example.ottomatic.engine.action.ScreenTimeoutAction
import com.example.ottomatic.engine.action.ScriptAction
import com.example.ottomatic.engine.action.SetVariableAction
import com.example.ottomatic.engine.action.ShowMessageAction
import com.example.ottomatic.engine.action.SendSmsAction
import com.example.ottomatic.engine.action.StopAction
import com.example.ottomatic.engine.action.StopSoundAction
import com.example.ottomatic.engine.action.VibrateAction
import com.example.ottomatic.engine.action.VolumeAction
import com.example.ottomatic.engine.action.WaitUntilAction
import com.example.ottomatic.engine.action.WhileAction
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
 * but a comparison over a value. [WhileAction] shares that same comparison outright
 * (see [COMPARISON_TYPE_IDS]) and differs only in doing the answer again rather than
 * branching on it.
 *
 * [ForEachAction], [RepeatAction] and [WhileAction] are the graph's only iteration,
 * and are likewise registered here rather than anywhere special: a loop is an action
 * whose `body` the executor pulses more than once
 * ([com.example.ottomatic.engine.LoopAction],
 * [com.example.ottomatic.engine.ConditionalLoopAction]), not a node family of its own.
 */
object ActionRegistry {

    private val actions: List<ExecutableAction> = listOf(
        AutoRotateAction(),
        BluetoothAction(),
        BrightnessAction(),
        CallAction(),
        ClipboardAction(),
        // The two waits, together and in that order: waiting a while is the simpler
        // idea, and waiting *until* something is the one that also carries on.
        DelayAction(),
        WaitUntilAction(),
        // The four dialog nodes are one family and are kept together for the reason
        // the three loops are, with the plainest first: a message is what somebody
        // reaches for before they need an answer at all.
        ShowMessageAction(),
        AskConfirmAction(),
        AskInputAction(),
        AskChoiceAction(),
        DisableMacroAction(),
        DndAction(),
        EnableMacroAction(),
        FlashlightAction(),
        HttpAction(),
        IfAction(),
        LaunchAppAction(),
        ListAddAction(),
        ListClearAction(),
        LogAction(),
        NotifyAction(),
        OpenUrlAction(),
        PlaySoundAction(),
        // The palette renders in registry order, so the three loops are kept
        // together and out of alphabetical order deliberately: they are one family,
        // and "Repeat" leads because repeating a set number of times is the case
        // people come looking for.
        RepeatAction(),
        ForEachAction(),
        WhileAction(),
        RingerModeAction(),
        ScreenTimeoutAction(),
        ScriptAction(),
        SendSmsAction(),
        SetVariableAction(),
        StopAction(),
        StopSoundAction(),
        VibrateAction(),
        VolumeAction(),
        WifiAction(),
        BreakStructAction(),
    )

    private val byId: Map<NodeTypeId, ExecutableAction> = actions.associateBy { it.typeId }

    fun byId(typeId: NodeTypeId): ExecutableAction? = byId[typeId]

    fun all(): List<ExecutableAction> = actions
}
