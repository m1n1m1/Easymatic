package com.example.ottomatic.data.trigger

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.data.nfc.NfcReader
import com.example.ottomatic.data.nfc.ScannedTag
import com.example.ottomatic.domain.model.NfcTapFilter
import com.example.ottomatic.engine.service.MacroEngineService

/**
 * Where a tag tap arrives.
 *
 * An Activity, and not by preference: Android performs **no background NFC
 * scanning at all**. A tag is dispatched to an activity or to nobody, so the entry
 * point to a tag trigger has to be one — which also means a tap cannot reach a
 * phone whose screen is off or locked, and that limit belongs in the trigger's
 * description rather than only here.
 *
 * Invisible by construction and for [com.example.ottomatic.feature.shortcut.RunTriggerActivity]'s
 * reasons: translucent so nothing is drawn, `noHistory` so it leaves no back stack,
 * `excludeFromRecents` so it never shows up in recents, and an empty `taskAffinity`
 * so it does not attach itself to MainActivity's task and drag the app's window
 * into view behind it.
 *
 * Being the foreground activity for the instant this lives is also what makes the
 * foreground-service start below legal on Android 12+, which is why all of it
 * happens synchronously in [onCreate] rather than in a coroutine that would outlive
 * the exemption.
 */
class NfcTagActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    /**
     * The dispatch intent carries `FLAG_ACTIVITY_SINGLE_TOP`, and [finish] is not
     * synchronous — so a second tap during teardown is delivered here rather than
     * to a fresh instance. Without this override it would simply be dropped, and
     * the [NfcTapFilter] would make that look like correct de-duplication.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    // Three guards and the publish. Folding them into one chain would hide which
    // of "not a tag intent", "a tag with no id" and "the same tap again" fired.
    @Suppress("ReturnCount")
    private fun handle(intent: Intent?) {
        val scan = NfcReader.fromIntent(intent) ?: return
        if (scan.uid.isBlank()) {
            Log.w(TAG, "A tag was tapped but reported no id, so nothing can match it")
            return
        }
        if (!tapFilter.accept(scan.uid, System.currentTimeMillis())) return
        publish(scan)
    }

    /**
     * Parks the tap for whatever ends up collecting, then asks the engine to arm.
     *
     * In that order, and both halves matter. This tap is usually what *started* the
     * process — nothing is subscribed to the bus yet, and a plain emit onto a
     * replay-free bus is discarded on the spot — so it goes through
     * [TriggerBus.emitOrHoldBroadcast], which delivers live if anything is listening
     * and parks a copy while the engine is still coming up either way. Parking
     * first is what makes the ordering not matter, since `startForegroundService`
     * can reach `onCreate` synchronously on some paths.
     *
     * It is addressed to [NodeId.BROADCAST] rather than to particular trigger
     * nodes, because finding those would mean reading and parsing every enabled
     * workflow off disk on the tap path — `WorkflowSummary` carries no nodes — and
     * that is exactly the work `GeofenceReceiver` refuses to do for the same reason.
     * A geofence knows its node id because the fence was registered under it; a tag
     * knows nothing about this app at all.
     */
    private fun publish(scan: ScannedTag) {
        TriggerBus.emitOrHoldBroadcast(
            TriggerEvent(
                source = TriggerSource.NFC,
                triggerNodeId = NodeId.BROADCAST,
                payload = buildMap {
                    put(KEY_TAG_ID, scan.uid)
                    put(KEY_TEXT, scan.text)
                    put(KEY_TIMESTAMP, System.currentTimeMillis().toString())
                },
            ),
        )
        runCatching { MacroEngineService.start(this, MacroEngineService.ACTION_REARM_ALL) }
            .onFailure { Log.w(TAG, "Could not start the engine for an NFC tap", it) }
    }

    companion object {
        /**
         * Process-scoped, because the Activity is not: each tap builds a new
         * instance, so a per-instance filter would never see the tap it is meant to
         * be comparing against.
         */
        private val tapFilter = NfcTapFilter()

        const val KEY_TAG_ID = "tagId"
        const val KEY_TEXT = "text"
        const val KEY_TIMESTAMP = "timestamp"

        private const val TAG = "Ottomatic"
    }
}
