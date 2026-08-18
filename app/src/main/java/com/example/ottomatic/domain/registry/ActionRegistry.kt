package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.engine.ExecutableAction
import com.example.ottomatic.engine.action.AiDescribeAction
import com.example.ottomatic.engine.action.AiPromptAction
import com.example.ottomatic.engine.action.AskChoiceAction
import com.example.ottomatic.engine.action.AskConfirmAction
import com.example.ottomatic.engine.action.AskInputAction
import com.example.ottomatic.engine.action.AutoRotateAction
import com.example.ottomatic.engine.action.BluetoothAction
import com.example.ottomatic.engine.action.BreakStructAction
import com.example.ottomatic.engine.action.BrightnessAction
import com.example.ottomatic.engine.action.CalendarAddAction
import com.example.ottomatic.engine.action.CalendarQueryAction
import com.example.ottomatic.engine.action.CalendarUpdateAction
import com.example.ottomatic.engine.action.CallAction
import com.example.ottomatic.engine.action.CameraPhotoAction
import com.example.ottomatic.engine.action.ClipboardAction
import com.example.ottomatic.engine.action.DelayAction
import com.example.ottomatic.engine.action.DisableMacroAction
import com.example.ottomatic.engine.action.DndAction
import com.example.ottomatic.engine.action.EnableMacroAction
import com.example.ottomatic.engine.action.FetchMailAction
import com.example.ottomatic.engine.action.FileDeleteAction
import com.example.ottomatic.engine.action.FileInfoAction
import com.example.ottomatic.engine.action.FileListAction
import com.example.ottomatic.engine.action.FileReadAction
import com.example.ottomatic.engine.action.FileTransferAction
import com.example.ottomatic.engine.action.FileWriteAction
import com.example.ottomatic.engine.action.FlashlightAction
import com.example.ottomatic.engine.action.ForEachAction
import com.example.ottomatic.engine.action.HaServiceAction
import com.example.ottomatic.engine.action.HttpAction
import com.example.ottomatic.engine.action.IfAction
import com.example.ottomatic.engine.action.ImageDeleteAction
import com.example.ottomatic.engine.action.ImageEditAction
import com.example.ottomatic.engine.action.ImageInfoAction
import com.example.ottomatic.engine.action.ImageListAction
import com.example.ottomatic.engine.action.ImageMetadataAction
import com.example.ottomatic.engine.action.ImageMoveAction
import com.example.ottomatic.engine.action.LaunchAppAction
import com.example.ottomatic.engine.action.LightControlAction
import com.example.ottomatic.engine.action.LightSceneAction
import com.example.ottomatic.engine.action.LightStateAction
import com.example.ottomatic.engine.action.ListAddAction
import com.example.ottomatic.engine.action.ListClearAction
import com.example.ottomatic.engine.action.LogAction
import com.example.ottomatic.engine.action.MailUpdateAction
import com.example.ottomatic.engine.action.MqttPublishAction
import com.example.ottomatic.engine.action.NotificationActionAction
import com.example.ottomatic.engine.action.NotifyAction
import com.example.ottomatic.engine.action.NotifyCancelAction
import com.example.ottomatic.engine.action.OpenUrlAction
import com.example.ottomatic.engine.action.MediaControlAction
import com.example.ottomatic.engine.action.MediaSeekAction
import com.example.ottomatic.engine.action.PlaySoundAction
import com.example.ottomatic.engine.action.RecordAudioAction
import com.example.ottomatic.engine.action.RecordStartAction
import com.example.ottomatic.engine.action.RecordStopAction
import com.example.ottomatic.engine.action.RepeatAction
import com.example.ottomatic.engine.action.ReplyMessageAction
import com.example.ottomatic.engine.action.RingerModeAction
import com.example.ottomatic.engine.action.ScreenTimeoutAction
import com.example.ottomatic.engine.action.ScreenshotAction
import com.example.ottomatic.engine.action.ScriptAction
import com.example.ottomatic.engine.action.SendMailAction
import com.example.ottomatic.engine.action.SendMessageAction
import com.example.ottomatic.engine.action.SendSmsAction
import com.example.ottomatic.engine.action.SetVariableAction
import com.example.ottomatic.engine.action.ShowMessageAction
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
        AiPromptAction(),
        AiDescribeAction(),
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
        // The light family, kept together for the mail family's reason: control one,
        // recall a whole arrangement, and read back what is actually on right now.
        LightControlAction(),
        LightSceneAction(),
        LightStateAction(),
        HaServiceAction(),
        MqttPublishAction(),
        // The file family, kept together on the mail family's reasoning and in the
        // order somebody meets them: put something somewhere, read it back, find out
        // what is there, then tidy up.
        FileWriteAction(),
        FileReadAction(),
        FileListAction(),
        FileInfoAction(),
        FileTransferAction(),
        FileDeleteAction(),

        // The picture family, after the file family and grouped the same way: the
        // order somebody meets them is find one, look at it, then change it — read
        // before write, and the destructive one last.
        //
        // Screenshot leads it, ahead of even the read: it is the one node here that
        // *makes* a picture rather than addressing one that already existed, so it is
        // where a macro with no picture yet starts. Everything below it needs one.
        //
        // There are two makers now, and the screen leads the pair: it needs no hardware, no
        // runtime grant and no light, so it is the one that works on every phone. The camera
        // follows it immediately rather than joining the readers below, because what those
        // two have in common — producing a picture out of nothing — is what somebody
        // scanning this list is looking for.
        ScreenshotAction(),
        CameraPhotoAction(),
        ImageListAction(),
        ImageInfoAction(),
        ImageEditAction(),
        ImageMetadataAction(),
        ImageMoveAction(),
        ImageDeleteAction(),

        // The recording family, after the pictures and on their placement rule read once
        // more: the makers come first, and within them the one that needs the least graph
        // around it. `record_audio` is a whole recording in one node, where the start/stop
        // pair only means anything as a pair — so somebody meeting these in order meets the
        // simple case first and the mechanism second. `record_stop` follows `record_start`
        // because neither node is any use without the other and the order is the order they
        // are placed in.
        RecordAudioAction(),
        RecordStartAction(),
        RecordStopAction(),
        ListAddAction(),
        ListClearAction(),
        LogAction(),
        // Posting a notification and taking it down again are one pair, in the order
        // they happen — the second is meaningless without a tag the first handed out.
        NotifyAction(),
        NotifyCancelAction(),
        // The mail family, kept together the way the loops and the dialogs are:
        // send, read, and act on what was read.
        SendMailAction(),
        FetchMailAction(),
        MailUpdateAction(),
        // The calendar family, kept together on the mail family's reasoning and in the
        // same order: read the diary, put something in it, then act on what was read.
        // "Find" leads here rather than "Add" because a macro that writes into a calendar
        // almost always looked at it first.
        CalendarQueryAction(),
        CalendarAddAction(),
        CalendarUpdateAction(),
        // The messenger family, kept together on the mail family's reasoning and in
        // the same order: send into a conversation, then act on the notification it
        // came from. "Send Message" is third because it is the one that cannot send
        // on its own — it opens the app with the message ready.
        ReplyMessageAction(),
        NotificationActionAction(),
        SendMessageAction(),
        OpenUrlAction(),
        // The two media nodes together and next to the sound family, because that is what
        // somebody scanning for "make the music stop" is looking at — even though only one
        // of the four concerns this app's own sound.
        MediaControlAction(),
        MediaSeekAction(),
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
