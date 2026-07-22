package com.example.ottomatic.data.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource

/**
 * Manifest-registered receiver for media-related broadcasts: media-button
 * key presses and media mount / unmount / eject of external storage.
 *
 * Payload contract:
 * - `triggerType` ∈ `"media_button"`, `"media_mount"`
 * - `event` — `"button"`, `"mounted"`, `"unmounted"`, `"ejected"`
 * - `detail` — key name (for media_button) or mount path (for media_mount)
 * - `timestamp` — epoch ms
 */
class MediaReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_MEDIA_BUTTON -> handleMediaButton(intent)
            Intent.ACTION_MEDIA_MOUNTED -> emitMedia("media_mount", "mounted", intent.data?.path)
            Intent.ACTION_MEDIA_UNMOUNTED -> emitMedia("media_mount", "unmounted", intent.data?.path)
            Intent.ACTION_MEDIA_EJECT -> emitMedia("media_mount", "ejected", intent.data?.path)
        }
    }

    private fun handleMediaButton(intent: Intent) {
        @Suppress("DEPRECATION")
        val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        val keyName = when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "play_pause"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous"
            KeyEvent.KEYCODE_MEDIA_STOP -> "stop"
            KeyEvent.KEYCODE_HEADSETHOOK -> "headset_hook"
            else -> "key_${keyEvent.keyCode}"
        }
        emitMedia("media_button", "button", keyName)
    }

    private fun emitMedia(triggerType: String, event: String, detail: String?) {
        val payload = buildMap {
            put(KEY_TRIGGER_TYPE, triggerType)
            put(KEY_EVENT, event)
            put(KEY_TIMESTAMP, System.currentTimeMillis().toString())
            detail?.let { put(KEY_DETAIL, it) }
        }
        TriggerBus.emit(
            TriggerEvent(
                source = TriggerSource.MEDIA,
                triggerNodeId = NODE_ID_SENTINEL,
                payload = payload,
            ),
        )
    }

    companion object {
        const val NODE_ID_SENTINEL = "*"

        const val KEY_TRIGGER_TYPE = "triggerType"
        const val KEY_EVENT = "event"
        const val KEY_DETAIL = "detail"
        const val KEY_TIMESTAMP = "timestamp"
    }
}
