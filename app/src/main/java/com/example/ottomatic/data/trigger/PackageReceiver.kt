package com.example.ottomatic.data.trigger

import com.example.ottomatic.core.model.NodeId
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ottomatic.data.apps.InstalledApps
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource

/**
 * Manifest-registered receiver for package-install / removal / replacement
 * broadcasts. Emits a [TriggerEvent] with source [TriggerSource.PACKAGE].
 *
 * Payload contract:
 * - `triggerType` = `"app_installed"`
 * - `event` ∈ `"installed"`, `"removed"`, `"replaced"`
 * - `packageName` — the affected package
 * - `timestamp` — epoch ms
 */
class PackageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // The app picker's list is exactly what these broadcasts invalidate, and
        // this receiver is already running for every one of them.
        InstalledApps.invalidate()
        val packageName = intent.data?.schemeSpecificPart
        val event = when (action) {
            Intent.ACTION_PACKAGE_ADDED -> "installed"
            Intent.ACTION_PACKAGE_REMOVED -> "removed"
            Intent.ACTION_PACKAGE_REPLACED -> "replaced"
            else -> null
        }
        if (packageName == null || event == null) return
        TriggerBus.emit(
            TriggerEvent(
                source = TriggerSource.PACKAGE,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf(
                    KEY_TRIGGER_TYPE to "app_installed",
                    KEY_EVENT to event,
                    KEY_PACKAGE_NAME to packageName,
                    KEY_TIMESTAMP to System.currentTimeMillis().toString(),
                ),
            ),
        )
    }

    companion object {

        const val KEY_TRIGGER_TYPE = "triggerType"
        const val KEY_EVENT = "event"
        const val KEY_PACKAGE_NAME = "packageName"
        const val KEY_TIMESTAMP = "timestamp"
    }
}
