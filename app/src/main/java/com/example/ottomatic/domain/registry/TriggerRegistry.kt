package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.engine.trigger.ExecutableTrigger

/**
 * Central registry mapping a trigger [typeId] to its executable
 * [ExecutableTrigger].
 *
 * This list is the *only* registration step for a new trigger: the trigger's
 * own file declares everything else (metadata, ports, config fields and
 * output encoder) in its single
 * [com.example.ottomatic.engine.TriggerNodeDefinition]. [NodeTypeRegistry]
 * and [ConfigSchemaRegistry] derive their views from these definitions.
 */
object TriggerRegistry {

    private val triggers: List<ExecutableTrigger> = buildList {
        add(com.example.ottomatic.engine.trigger.ManualTrigger())
        // Immediately after the manual one because they are the same idea seen from
        // two sides: this list's order is the palette's, and "somebody asked for
        // this" is one place in it, whether the somebody is a thumb or another app.
        add(com.example.ottomatic.engine.trigger.ApiTrigger())
        add(com.example.ottomatic.engine.trigger.ScheduleTrigger())
        add(com.example.ottomatic.engine.trigger.SmsTrigger())
        // Beside the SMS trigger because it answers the same question, and not
        // among the broadcast tiers below because nothing broadcasts it: mail is
        // reached over the network, by a poll and by a held-open connection.
        add(com.example.ottomatic.engine.trigger.MailTrigger())
        // Beside the schedule trigger in spirit and here in the list, because that is
        // what somebody is looking at when they want this. Not among the broadcast tiers
        // below: nothing broadcasts a calendar event that an app may hear, which is why
        // both of these plan their own alarm and watch the provider themselves.
        add(com.example.ottomatic.engine.trigger.CalendarEventTrigger())
        add(com.example.ottomatic.engine.trigger.CalendarChangedTrigger())
        // Beside the mail trigger for its reason: nothing broadcasts these either — a
        // hub is reached over the network, by a connection held open for as long as the
        // engine runs. The state trigger leads because "when this entity changes" is
        // what nearly everybody wants; the event one is the escape hatch behind it.
        add(com.example.ottomatic.engine.trigger.HaStateTrigger())
        add(com.example.ottomatic.engine.trigger.HaEventTrigger())
        add(com.example.ottomatic.engine.trigger.MqttTrigger())
        add(com.example.ottomatic.engine.trigger.NotificationTrigger())
        // Directly after the generic notification trigger it is refined from, so the
        // palette shows the pair together: one fires on every notification, the other
        // only on the ones that are a message somebody sent.
        add(com.example.ottomatic.engine.trigger.MessageTrigger())
        add(com.example.ottomatic.engine.trigger.BootTrigger())
        add(com.example.ottomatic.engine.trigger.ChargingTrigger())
        add(com.example.ottomatic.engine.trigger.BatteryLevelTrigger())
        add(com.example.ottomatic.engine.trigger.GeofenceTrigger())
        // Not a Tier 1 broadcast trigger: an NFC tap is dispatched to an
        // Activity, because Android does no background tag scanning at all.
        add(com.example.ottomatic.engine.trigger.NfcTagTrigger())
        // Also not a Tier 1 broadcast trigger, and not for NFC's reason: this is a
        // ContentObserver over the media collection, routed to one node id because each
        // node keeps its own high-water mark. Placed here, beside the other prominent
        // triggers, rather than down with `trigger.media_mount` — the two share a word
        // and nothing else.
        add(com.example.ottomatic.engine.trigger.ImageSavedTrigger())
        // The same observer, narrowed — so it belongs here rather than in Tier 3 with the
        // other accessibility trigger, even though `action.screenshot` is accessibility's.
        // What arms this is a ContentObserver; taking a screenshot and hearing about one
        // are unrelated mechanisms that happen to share a subject.
        add(com.example.ottomatic.engine.trigger.ScreenshotTrigger())
        // Beside them by subject and unlike them by mechanism, which is why it sits at the
        // end of this group rather than inside it: there is no observer, no registration
        // table and no high-water mark here. The recorder in `data/` ended the recording
        // itself, so the event is broadcast and this node filters on the source alone.
        add(com.example.ottomatic.engine.trigger.RecordingSavedTrigger())
        // Tier 0 — engine-internal triggers.
        add(com.example.ottomatic.engine.trigger.EmptyTrigger())
        add(com.example.ottomatic.engine.trigger.AppInitTrigger())
        add(com.example.ottomatic.engine.trigger.MacroFinishedTrigger())
        add(com.example.ottomatic.engine.trigger.MacroEnabledTrigger())
        add(com.example.ottomatic.engine.trigger.ModeChangeTrigger())
        add(com.example.ottomatic.engine.trigger.VariableChangeTrigger())
        // Tier 1 — broadcast-receiver triggers.
        add(com.example.ottomatic.engine.trigger.WifiStateTrigger())
        add(com.example.ottomatic.engine.trigger.WifiNetworkTrigger())
        add(com.example.ottomatic.engine.trigger.BluetoothTrigger())
        add(com.example.ottomatic.engine.trigger.BluetoothConnectTrigger())
        add(com.example.ottomatic.engine.trigger.AirplaneModeTrigger())
        add(com.example.ottomatic.engine.trigger.CallStateTrigger())
        add(com.example.ottomatic.engine.trigger.HeadsetTrigger())
        add(com.example.ottomatic.engine.trigger.UsbDeviceTrigger())
        add(com.example.ottomatic.engine.trigger.DockTrigger())
        add(com.example.ottomatic.engine.trigger.ScreenTrigger())
        add(com.example.ottomatic.engine.trigger.UserPresentTrigger())
        add(com.example.ottomatic.engine.trigger.RingerModeTrigger())
        add(com.example.ottomatic.engine.trigger.PowerSaveTrigger())
        add(com.example.ottomatic.engine.trigger.ClockChangeTrigger())
        add(com.example.ottomatic.engine.trigger.LocaleChangeTrigger())
        add(com.example.ottomatic.engine.trigger.ShutdownTrigger())
        add(com.example.ottomatic.engine.trigger.AppInstalledTrigger())
        add(com.example.ottomatic.engine.trigger.MediaButtonTrigger())
        add(com.example.ottomatic.engine.trigger.MediaMountTrigger())
        // Tier 2 — sensor-backed gesture triggers.
        add(com.example.ottomatic.engine.trigger.DeviceOrientationTrigger())
        add(com.example.ottomatic.engine.trigger.ShakeTrigger())
        add(com.example.ottomatic.engine.trigger.DeviceTapTrigger())
        add(com.example.ottomatic.engine.trigger.DeviceMotionTrigger())
        add(com.example.ottomatic.engine.trigger.ProximityTrigger())
        add(com.example.ottomatic.engine.trigger.LightLevelTrigger())
        // Tier 3 — accessibility-service triggers.
        add(com.example.ottomatic.engine.trigger.VolumeButtonTrigger())
    }

    private val byId: Map<NodeTypeId, ExecutableTrigger> = triggers.associateBy { it.typeId }

    fun byId(typeId: NodeTypeId): ExecutableTrigger? = byId[typeId]

    fun all(): List<ExecutableTrigger> = triggers
}
