package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.engine.trigger.ExecutableTrigger

/**
 * Central registry mapping a trigger [typeId] to its executable
 * [ExecutableTrigger].
 *
 * This list is the *only* registration step for a new trigger: the trigger's
 * own file declares everything else (metadata, ports, config fields and
 * output encoder) in its single
 * [io.github.m1n1m1.easymatic.engine.TriggerNodeDefinition]. [NodeTypeRegistry]
 * and [ConfigSchemaRegistry] derive their views from these definitions.
 */
object TriggerRegistry {

    private val triggers: List<ExecutableTrigger> = buildList {
        add(io.github.m1n1m1.easymatic.engine.trigger.ManualTrigger())
        // Immediately after the manual one because they are the same idea seen from
        // two sides: this list's order is the palette's, and "somebody asked for
        // this" is one place in it, whether the somebody is a thumb or another app.
        add(io.github.m1n1m1.easymatic.engine.trigger.ApiTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.ScheduleTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.SmsTrigger())
        // Beside the SMS trigger because it answers the same question, and not
        // among the broadcast tiers below because nothing broadcasts it: mail is
        // reached over the network, by a poll and by a held-open connection.
        add(io.github.m1n1m1.easymatic.engine.trigger.MailTrigger())
        // Beside the schedule trigger in spirit and here in the list, because that is
        // what somebody is looking at when they want this. Not among the broadcast tiers
        // below: nothing broadcasts a calendar event that an app may hear, which is why
        // both of these plan their own alarm and watch the provider themselves.
        add(io.github.m1n1m1.easymatic.engine.trigger.CalendarEventTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.CalendarChangedTrigger())
        // Beside the mail trigger for its reason: nothing broadcasts these either — a
        // hub is reached over the network, by a connection held open for as long as the
        // engine runs. The state trigger leads because "when this entity changes" is
        // what nearly everybody wants; the event one is the escape hatch behind it.
        add(io.github.m1n1m1.easymatic.engine.trigger.HaStateTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.HaEventTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.MqttTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.NotificationTrigger())
        // Directly after the generic notification trigger it is refined from, so the
        // palette shows the pair together: one fires on every notification, the other
        // only on the ones that are a message somebody sent.
        add(io.github.m1n1m1.easymatic.engine.trigger.MessageTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.BootTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.ChargingTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.BatteryLevelTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.GeofenceTrigger())
        // Not a Tier 1 broadcast trigger: an NFC tap is dispatched to an
        // Activity, because Android does no background tag scanning at all.
        add(io.github.m1n1m1.easymatic.engine.trigger.NfcTagTrigger())
        // Also not a Tier 1 broadcast trigger, and not for NFC's reason: this is a
        // ContentObserver over the media collection, routed to one node id because each
        // node keeps its own high-water mark. Placed here, beside the other prominent
        // triggers, rather than down with `trigger.media_mount` — the two share a word
        // and nothing else.
        add(io.github.m1n1m1.easymatic.engine.trigger.ImageSavedTrigger())
        // The same observer, narrowed — so it belongs here rather than in Tier 3 with the
        // other accessibility trigger, even though `action.screenshot` is accessibility's.
        // What arms this is a ContentObserver; taking a screenshot and hearing about one
        // are unrelated mechanisms that happen to share a subject.
        add(io.github.m1n1m1.easymatic.engine.trigger.ScreenshotTrigger())
        // Beside them by subject and unlike them by mechanism, which is why it sits at the
        // end of this group rather than inside it: there is no observer, no registration
        // table and no high-water mark here. The recorder in `data/` ended the recording
        // itself, so the event is broadcast and this node filters on the source alone.
        add(io.github.m1n1m1.easymatic.engine.trigger.RecordingSavedTrigger())
        // Beside them because it is armed rather than broadcast, and not in Tier 1 with
        // `trigger.media_button` despite the shared word: that one hears a key press through
        // a manifest receiver, this one hears what a player is doing through a
        // MediaSessionManager listener that has to be registered and taken down again.
        add(io.github.m1n1m1.easymatic.engine.trigger.MediaPlaybackTrigger())
        // Tier 0 — engine-internal triggers.
        add(io.github.m1n1m1.easymatic.engine.trigger.EmptyTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.AppInitTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.MacroFinishedTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.MacroEnabledTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.ModeChangeTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.VariableChangeTrigger())
        // Tier 1 — broadcast-receiver triggers.
        add(io.github.m1n1m1.easymatic.engine.trigger.WifiStateTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.WifiNetworkTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.BluetoothTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.BluetoothConnectTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.AirplaneModeTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.CallStateTrigger())
        // Directly after the state trigger it refines, so the palette shows the pair
        // together — the arrangement `trigger.message` gets beside `trigger.notification`.
        // One fires on every phase of a call, the other only on the last, and only that
        // one can carry how long the call lasted and whether anybody picked it up.
        add(io.github.m1n1m1.easymatic.engine.trigger.CallEndedTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.HeadsetTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.UsbDeviceTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.DockTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.ScreenTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.UserPresentTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.LoginFailedTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.RingerModeTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.PowerSaveTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.ClockChangeTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.LocaleChangeTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.ShutdownTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.AppInstalledTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.MediaButtonTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.MediaMountTrigger())
        // Tier 2 — sensor-backed gesture triggers.
        add(io.github.m1n1m1.easymatic.engine.trigger.DeviceOrientationTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.ShakeTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.DeviceTapTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.DeviceMotionTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.ProximityTrigger())
        add(io.github.m1n1m1.easymatic.engine.trigger.LightLevelTrigger())
        // Tier 3 — accessibility-service triggers.
        add(io.github.m1n1m1.easymatic.engine.trigger.VolumeButtonTrigger())
        // Beside it by mechanism and away from it by subject, which is the split this
        // file keeps making: what arms this is the same accessibility service, so it
        // belongs in Tier 3, while its category is SENSORS because a swipe is a
        // gesture and that is where somebody looks for one.
        add(io.github.m1n1m1.easymatic.engine.trigger.FingerprintGestureTrigger())
    }

    private val byId: Map<NodeTypeId, ExecutableTrigger> = triggers.associateBy { it.typeId }

    fun byId(typeId: NodeTypeId): ExecutableTrigger? = byId[typeId]

    fun all(): List<ExecutableTrigger> = triggers
}
