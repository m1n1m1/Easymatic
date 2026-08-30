package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.engine.ExecutableAction
import io.github.m1n1m1.easymatic.engine.action.AiDescribeAction
import io.github.m1n1m1.easymatic.engine.action.AiPromptAction
import io.github.m1n1m1.easymatic.engine.action.TranscribeAction
import io.github.m1n1m1.easymatic.engine.action.TranscribeEndAction
import io.github.m1n1m1.easymatic.engine.action.TranscribeFileAction
import io.github.m1n1m1.easymatic.engine.action.TranscribeStartAction
import io.github.m1n1m1.easymatic.engine.action.TranslateAction
import io.github.m1n1m1.easymatic.engine.action.AskChoiceAction
import io.github.m1n1m1.easymatic.engine.action.AskConfirmAction
import io.github.m1n1m1.easymatic.engine.action.AskInputAction
import io.github.m1n1m1.easymatic.engine.action.AutoRotateAction
import io.github.m1n1m1.easymatic.engine.action.BluetoothAction
import io.github.m1n1m1.easymatic.engine.action.BreakStructAction
import io.github.m1n1m1.easymatic.engine.action.BrightnessAction
import io.github.m1n1m1.easymatic.engine.action.BroadcastIntentAction
import io.github.m1n1m1.easymatic.engine.action.CalendarAddAction
import io.github.m1n1m1.easymatic.engine.action.CalendarQueryAction
import io.github.m1n1m1.easymatic.engine.action.CalendarUpdateAction
import io.github.m1n1m1.easymatic.engine.action.CallAction
import io.github.m1n1m1.easymatic.engine.action.CameraPhotoAction
import io.github.m1n1m1.easymatic.engine.action.ClipboardAction
import io.github.m1n1m1.easymatic.engine.action.DelayAction
import io.github.m1n1m1.easymatic.engine.action.DisableMacroAction
import io.github.m1n1m1.easymatic.engine.action.DndAction
import io.github.m1n1m1.easymatic.engine.action.EnableMacroAction
import io.github.m1n1m1.easymatic.engine.action.FetchMailAction
import io.github.m1n1m1.easymatic.engine.action.FileDeleteAction
import io.github.m1n1m1.easymatic.engine.action.FileInfoAction
import io.github.m1n1m1.easymatic.engine.action.FileListAction
import io.github.m1n1m1.easymatic.engine.action.FileReadAction
import io.github.m1n1m1.easymatic.engine.action.FileTransferAction
import io.github.m1n1m1.easymatic.engine.action.FileWriteAction
import io.github.m1n1m1.easymatic.engine.action.FlashlightAction
import io.github.m1n1m1.easymatic.engine.action.ForEachAction
import io.github.m1n1m1.easymatic.engine.action.HaServiceAction
import io.github.m1n1m1.easymatic.engine.action.HttpAction
import io.github.m1n1m1.easymatic.engine.action.IfAction
import io.github.m1n1m1.easymatic.engine.action.ImageDeleteAction
import io.github.m1n1m1.easymatic.engine.action.ImageEditAction
import io.github.m1n1m1.easymatic.engine.action.ImageInfoAction
import io.github.m1n1m1.easymatic.engine.action.ImageListAction
import io.github.m1n1m1.easymatic.engine.action.ImageMetadataAction
import io.github.m1n1m1.easymatic.engine.action.ImageMoveAction
import io.github.m1n1m1.easymatic.engine.action.LaunchAppAction
import io.github.m1n1m1.easymatic.engine.action.LightControlAction
import io.github.m1n1m1.easymatic.engine.action.LightSceneAction
import io.github.m1n1m1.easymatic.engine.action.LightStateAction
import io.github.m1n1m1.easymatic.engine.action.ListAddAction
import io.github.m1n1m1.easymatic.engine.action.ListClearAction
import io.github.m1n1m1.easymatic.engine.action.ListenAction
import io.github.m1n1m1.easymatic.engine.action.LogAction
import io.github.m1n1m1.easymatic.engine.action.MailUpdateAction
import io.github.m1n1m1.easymatic.engine.action.MediaControlAction
import io.github.m1n1m1.easymatic.engine.action.MediaSeekAction
import io.github.m1n1m1.easymatic.engine.action.MqttPublishAction
import io.github.m1n1m1.easymatic.engine.action.NotificationActionAction
import io.github.m1n1m1.easymatic.engine.action.NotifyAction
import io.github.m1n1m1.easymatic.engine.action.NotifyCancelAction
import io.github.m1n1m1.easymatic.engine.action.OpenUrlAction
import io.github.m1n1m1.easymatic.engine.action.PlaySoundAction
import io.github.m1n1m1.easymatic.engine.action.RecordAudioAction
import io.github.m1n1m1.easymatic.engine.action.RecordStartAction
import io.github.m1n1m1.easymatic.engine.action.RecordStopAction
import io.github.m1n1m1.easymatic.engine.action.RepeatAction
import io.github.m1n1m1.easymatic.engine.action.ReplyMessageAction
import io.github.m1n1m1.easymatic.engine.action.RingerModeAction
import io.github.m1n1m1.easymatic.engine.action.ScreenRotationAction
import io.github.m1n1m1.easymatic.engine.action.ScreenTimeoutAction
import io.github.m1n1m1.easymatic.engine.action.ScreenshotAction
import io.github.m1n1m1.easymatic.engine.action.ScriptAction
import io.github.m1n1m1.easymatic.engine.action.SendIntentAction
import io.github.m1n1m1.easymatic.engine.action.SendMailAction
import io.github.m1n1m1.easymatic.engine.action.SendMessageAction
import io.github.m1n1m1.easymatic.engine.action.SendSmsAction
import io.github.m1n1m1.easymatic.engine.action.SetVariableAction
import io.github.m1n1m1.easymatic.engine.action.ShowMessageAction
import io.github.m1n1m1.easymatic.engine.action.SpeakAction
import io.github.m1n1m1.easymatic.engine.action.SpeakStopAction
import io.github.m1n1m1.easymatic.engine.action.StopAction
import io.github.m1n1m1.easymatic.engine.action.StopSoundAction
import io.github.m1n1m1.easymatic.engine.action.VibrateAction
import io.github.m1n1m1.easymatic.engine.action.VolumeAction
import io.github.m1n1m1.easymatic.engine.action.WaitUntilAction
import io.github.m1n1m1.easymatic.engine.action.WhileAction
import io.github.m1n1m1.easymatic.engine.action.WifiAction

/**
 * Central registry mapping an action [typeId] to its executable implementation.
 *
 * This list is the *only* registration step for a new [Action]: the action's
 * own file declares everything else (metadata, ports, config fields and
 * contract) in its single [io.github.m1n1m1.easymatic.engine.ActionNodeDefinition].
 * [NodeTypeRegistry] and [ConfigSchemaRegistry] derive their views from these
 * definitions. The single adaptive [BreakStructAction] splits any
 * `@Serializable` struct into its fields at runtime; per-field data outputs
 * are exposed directly on each node via
 * [io.github.m1n1m1.easymatic.domain.registry.effectivePorts] (no dedicated
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
 * ([io.github.m1n1m1.easymatic.engine.LoopAction],
 * [io.github.m1n1m1.easymatic.engine.ConditionalLoopAction]), not a node family of its own.
 */
object ActionRegistry {

    private val actions: List<ExecutableAction> = listOf(
        AiPromptAction(),
        AiDescribeAction(),
        // The two media questions sit beside the picture one rather than with the
        // recording family: what a user is looking for here is "ask AI about a…", and
        // the palette renders in this order.
        // The one-shot first, then the start/end pair, then the file one — `action`
        // `.record_audio`'s family order and for its reason: the node most macros want is
        // the one that needs no partner.
        TranscribeAction(),
        TranscribeStartAction(),
        TranscribeEndAction(),
        TranscribeFileAction(),
        // Last in the AI block, and after the transcribe family rather than before it,
        // because the palette renders in this order and the four above are one family a
        // reader scans as a unit. It belongs in the block at all for the reason the
        // `PHONE` half of `action.transcribe` does: what these have in common is a model
        // turning one kind of language into another, not an account being billed for it.
        TranslateAction(),
        // The two rotation nodes together, on the notify pair's reasoning: turning the
        // screen is what somebody comes looking for, and it only holds because it
        // switched the toggle above it off — so the pair is only readable side by side.
        AutoRotateAction(),
        ScreenRotationAction(),
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
        // The three voice nodes, immediately after the four dialog ones because they are
        // the same family reached through a different channel: Speak is Show Message with
        // nowhere to draw, and Listen is Ask for Input with the user's hands full. Speaking
        // comes first for the reason a message does — it needs no answer — and Stop Speaking
        // follows the node it exists to undo, exactly as `record_stop` follows `record_start`.
        SpeakAction(),
        SpeakStopAction(),
        ListenAction(),
        DisableMacroAction(),
        DndAction(),
        EnableMacroAction(),
        FlashlightAction(),
        HttpAction(),
        IfAction(),
        LaunchAppAction(),
        // The general case, immediately after the two special cases it subsumes: somebody
        // reaches for this *when Launch App is not enough*, so it has to be visible from there.
        // The registry already breaks alphabetical order for a family and this is one. Activity
        // first — it is the one whose effect you can see, and the one people arrive looking for.
        SendIntentAction(),
        BroadcastIntentAction(),
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
