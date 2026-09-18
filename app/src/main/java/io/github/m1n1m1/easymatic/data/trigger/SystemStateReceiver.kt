package io.github.m1n1m1.easymatic.data.trigger

import io.github.m1n1m1.easymatic.core.model.NodeId
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.data.call.CallSessions
import io.github.m1n1m1.easymatic.data.call.DefaultDialer
import io.github.m1n1m1.easymatic.data.call.toPayload

/**
 * Manifest-registered receiver for Tier 1 system-state broadcasts that can be
 * declared in the manifest (everything except screen / user-present / time-tick,
 * which require runtime registration — see [ScreenBroadcastBridge]).
 *
 * Each action is mapped to a ([TriggerSource], `triggerType`, `event`) triple
 * in the payload so the engine-side trigger classes can filter precisely.
 *
 * Payload contract (shared by all triggers served here):
 * - `triggerType` — identifies which trigger the event belongs to
 *   (e.g. `"wifi_state"`, `"bluetooth"`, `"airplane_mode"`, …)
 * - `event` — the state transition that occurred
 * - `detail` — optional extra (device name, dock type, timezone id, …)
 * - `timestamp` — epoch ms
 */
class SystemStateReceiver : BroadcastReceiver() {

    @Suppress("ReturnCount") // No action, a call handled elsewhere, and nothing mapped.
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // A call is not a Tier 1 system state and stopped being modelled as one: it has a
        // beginning, a middle and an end that belong to one another, which `SystemState`
        // has nowhere to carry, and half of it arrives from notifications rather than
        // from any broadcast. `CallSessions` owns that; this just feeds it.
        if (action == ACTION_PHONE_STATE_CHANGED) {
            emitCall(context, intent)
            return
        }
        val mapping = ACTION_MAPPINGS[action]
        val event = resolveEvent(action, intent, context)
        if (mapping == null || event == null) return
        TriggerBus.emit(
            TriggerEvent(
                source = mapping.source,
                triggerNodeId = NodeId.BROADCAST,
                payload = buildMap {
                    put(KEY_TRIGGER_TYPE, mapping.triggerType)
                    put(KEY_EVENT, event)
                    put(KEY_TIMESTAMP, System.currentTimeMillis().toString())
                    mapping.detailExtractor(intent)?.let { put(KEY_DETAIL, it) }
                },
            ),
        )
    }

    /**
     * The cellular half of a call, handed to [CallSessions] to be merged with whatever
     * the call app's own notification said about it.
     *
     * The broadcast spells its states in capitals (`RINGING`, `OFFHOOK`, `IDLE`);
     * [CallSessions] lower-cases them. That mismatch is what made the old
     * `trigger.call_state` filter dead — the payload carried `"RINGING"` and the trigger
     * compared it against the enum entry lower-cased, so every selected filter matched
     * nothing and only "Any" ever fired.
     *
     * A missing state extra is **not** read as idle, which is what this used to do: idle
     * closes a session and reports a call ended, and inventing that from an
     * unintelligible broadcast would end a call that is still going on.
     */
    private fun emitCall(context: Context, intent: Intent) {
        val state = intent.getStringExtra(EXTRA_PHONE_STATE) ?: return
        val dialerPackage = DefaultDialer.packageName(context)
        val transition = CallSessions.onTelephony(
            state = state,
            nowMs = System.currentTimeMillis(),
            dialerPackage = dialerPackage,
            dialerAppName = DefaultDialer.appName(context, dialerPackage),
        ) ?: return
        TriggerBus.emitOrHoldBroadcast(
            TriggerEvent(
                source = TriggerSource.CALL,
                triggerNodeId = NodeId.BROADCAST,
                payload = transition.toPayload(),
            ),
        )
    }

    private fun resolveEvent(action: String, intent: Intent, context: Context): String? {
        return resolveConnectivityEvent(action, intent)
            ?: resolveHardwareEvent(action, intent)
            ?: resolveDisplayEvent(action, intent, context)
            ?: resolveSystemEvent(action, intent)
    }

    private fun resolveConnectivityEvent(action: String, intent: Intent): String? = when (action) {
        ACTION_WIFI_STATE_CHANGED -> resolveWifiState(intent)
        ACTION_BT_STATE_CHANGED -> resolveBluetoothState(intent)
        ACTION_BT_ACL_CONNECTED -> "connected"
        ACTION_BT_ACL_DISCONNECTED -> "disconnected"
        Intent.ACTION_AIRPLANE_MODE_CHANGED ->
            if (intent.getBooleanExtra(EXTRA_AIRPLANE_STATE, false)) "on" else "off"
        else -> null
    }

    private fun resolveWifiState(intent: Intent): String? = when (intent.getIntExtra(EXTRA_WIFI_STATE, -1)) {
        WIFI_STATE_ENABLED -> "enabled"
        WIFI_STATE_DISABLED -> "disabled"
        else -> null
    }

    private fun resolveBluetoothState(intent: Intent): String? = when (intent.getIntExtra(EXTRA_BT_STATE, -1)) {
        BT_STATE_ON -> "on"
        BT_STATE_OFF -> "off"
        else -> null
    }

    private fun resolveHardwareEvent(action: String, intent: Intent): String? = when (action) {
        Intent.ACTION_HEADSET_PLUG -> when (intent.getIntExtra(EXTRA_HEADSET_STATE, -1)) {
            HEADSET_PLUGGED -> "plugged"
            HEADSET_UNPLUGGED -> "unplugged"
            else -> null
        }
        ACTION_USB_ATTACHED -> "connected"
        ACTION_USB_DETACHED -> "disconnected"
        Intent.ACTION_DOCK_EVENT -> when (intent.getIntExtra(EXTRA_DOCK_STATE, -1)) {
            DOCK_UNDOCKED -> "undocked"
            else -> "docked"
        }
        else -> null
    }

    private fun resolveDisplayEvent(action: String, intent: Intent, context: Context): String? =
        when (action) {
            ACTION_RINGER_MODE_CHANGED -> when (intent.getIntExtra(EXTRA_RINGER_MODE, -1)) {
                AudioManager.RINGER_MODE_NORMAL -> "normal"
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> null
            }
            ACTION_POWER_SAVE_MODE_CHANGED -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm?.isPowerSaveMode == true) "on" else "off"
            }
            else -> null
        }

    // The two clock actions share the `clock_change` trigger type and are told
    // apart by their event, so `trigger.clock_changed` can filter between them.
    private fun resolveSystemEvent(action: String, intent: Intent): String? = when (action) {
        Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
        Intent.ACTION_DATE_CHANGED -> "date_changed"
        // A per-app language change — the App language setting, or the phone's own
        // page for this app — arrives as the same action with the app named in
        // EXTRA_PACKAGE_NAME, where a change to the phone's language names nobody.
        // The phone's language did not change, so `trigger.locale_change` stays quiet.
        Intent.ACTION_LOCALE_CHANGED -> if (intent.hasExtra(Intent.EXTRA_PACKAGE_NAME)) null else "changed"
        Intent.ACTION_SHUTDOWN -> "shutdown"
        else -> null
    }

    private data class ActionMapping(
        val source: TriggerSource,
        val triggerType: String,
        val detailExtractor: ((Intent) -> String?) = { null },
    )

    companion object {

        const val KEY_TRIGGER_TYPE = "triggerType"
        const val KEY_EVENT = "event"
        const val KEY_DETAIL = "detail"
        const val KEY_TIMESTAMP = "timestamp"

        // Bluetooth constants (not all exposed in Intent for all API levels)
        const val ACTION_BT_STATE_CHANGED = "android.bluetooth.adapter.action.STATE_CHANGED"
        const val ACTION_BT_ACL_CONNECTED = "android.bluetooth.device.action.ACL_CONNECTED"
        const val ACTION_BT_ACL_DISCONNECTED = "android.bluetooth.device.action.ACL_DISCONNECTED"
        const val EXTRA_BT_STATE = "android.bluetooth.adapter.extra.STATE"
        const val BT_STATE_ON = 12
        const val BT_STATE_OFF = 10

        // USB constants
        const val ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
        const val ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"

        // Wi-Fi state extra key
        const val EXTRA_WIFI_STATE = "wifi_state"
        const val WIFI_STATE_ENABLED = 3
        const val WIFI_STATE_DISABLED = 1
        const val ACTION_WIFI_STATE_CHANGED = "android.net.wifi.WIFI_STATE_CHANGED"

        // Headset plug constants
        const val EXTRA_HEADSET_STATE = "state"
        const val HEADSET_PLUGGED = 1
        const val HEADSET_UNPLUGGED = 0

        // Dock event constants
        const val EXTRA_DOCK_STATE = "android.intent.extra.DOCK_STATE"
        const val DOCK_UNDOCKED = 0

        // Ringer mode constant
        const val ACTION_RINGER_MODE_CHANGED = "android.media.RINGER_MODE_CHANGED"
        const val EXTRA_RINGER_MODE = "android.media.EXTRA_RINGER_MODE"

        // Power save mode
        const val ACTION_POWER_SAVE_MODE_CHANGED = "android.os.action.POWER_SAVE_MODE_CHANGED"
        const val EXTRA_AIRPLANE_STATE = "state"

        // Phone state
        const val ACTION_PHONE_STATE_CHANGED = "android.intent.action.PHONE_STATE"
        const val EXTRA_PHONE_STATE = "state"

        @Suppress("LongMethod")
        private val ACTION_MAPPINGS: Map<String, ActionMapping> = buildMap {
            // Connectivity
            put(
                ACTION_WIFI_STATE_CHANGED,
                ActionMapping(TriggerSource.CONNECTIVITY, "wifi_state"),
            )
            put(
                ACTION_BT_STATE_CHANGED,
                ActionMapping(TriggerSource.CONNECTIVITY, "bluetooth"),
            )
            put(
                ACTION_BT_ACL_CONNECTED,
                ActionMapping(TriggerSource.CONNECTIVITY, "bluetooth_connect") {
                    it.getStringExtra("android.bluetooth.device.extra.NAME")
                },
            )
            put(
                ACTION_BT_ACL_DISCONNECTED,
                ActionMapping(TriggerSource.CONNECTIVITY, "bluetooth_connect") {
                    it.getStringExtra("android.bluetooth.device.extra.NAME")
                },
            )
            put(
                Intent.ACTION_AIRPLANE_MODE_CHANGED,
                ActionMapping(TriggerSource.CONNECTIVITY, "airplane_mode"),
            )
            // Hardware
            put(
                Intent.ACTION_HEADSET_PLUG,
                ActionMapping(TriggerSource.HARDWARE, "headset") {
                    if (it.getIntExtra("microphone", 0) == 1) "headset_with_mic" else "headphones"
                },
            )
            put(
                ACTION_USB_ATTACHED,
                ActionMapping(TriggerSource.HARDWARE, "usb_device"),
            )
            put(
                ACTION_USB_DETACHED,
                ActionMapping(TriggerSource.HARDWARE, "usb_device"),
            )
            put(
                Intent.ACTION_DOCK_EVENT,
                ActionMapping(TriggerSource.HARDWARE, "dock") {
                    when (it.getIntExtra(EXTRA_DOCK_STATE, -1)) {
                        1 -> "car"
                        2 -> "desk"
                        else -> "unknown"
                    }
                },
            )
            // Display / audio
            put(
                ACTION_RINGER_MODE_CHANGED,
                ActionMapping(TriggerSource.DISPLAY, "ringer_mode"),
            )
            put(
                ACTION_POWER_SAVE_MODE_CHANGED,
                ActionMapping(TriggerSource.DISPLAY, "power_save"),
            )
            // System
            put(
                Intent.ACTION_TIMEZONE_CHANGED,
                ActionMapping(TriggerSource.SYSTEM, "clock_change") {
                    it.getStringExtra("time-zone")
                },
            )
            put(
                Intent.ACTION_DATE_CHANGED,
                ActionMapping(TriggerSource.SYSTEM, "clock_change"),
            )
            put(
                Intent.ACTION_LOCALE_CHANGED,
                ActionMapping(TriggerSource.SYSTEM, "locale_change"),
            )
            put(
                Intent.ACTION_SHUTDOWN,
                ActionMapping(TriggerSource.SYSTEM, "shutdown"),
            )
        }
    }
}
