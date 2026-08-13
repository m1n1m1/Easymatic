package com.example.ottomatic.feature.sound

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import com.example.ottomatic.feature.grapheditor.PickerFieldChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A `@Picker(PickerKind.SOUND)` field: shows the chosen sound's name and offers
 * two ways to change it.
 *
 * Two, because neither chooser can reach the other's sounds. The system
 * ringtone chooser lists only what the media store has *registered* as a
 * ringtone, notification or alarm — the device's own cues, named as the user
 * knows them, but nothing else; an arbitrary audio file sitting in Downloads or
 * on an SD card simply is not in it. The document picker reaches any file any
 * storage provider offers, including cloud ones, but knows nothing about which
 * of them the device considers a ringtone.
 *
 * Both store the same thing: the picked content URI as text.
 */
@Composable
fun SoundPickerField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }

    // Naming a sound queries a content provider, so it stays off the
    // composition thread and is redone only when the chosen sound changes.
    val display by produceState(initialValue = "", value, context) {
        this.value = withContext(Dispatchers.IO) { soundName(context, value) }
    }

    // Resolved here: the intent is built outside the composition.
    val pickerTitle = stringResource(R.string.sound_choose_a_sound)

    // Previews provide no ActivityResultRegistryOwner; this field is only ever
    // shown inside the editor, which lives in a ComponentActivity.
    val ringtones = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        // The chooser answers with an extra, not with the intent's data uri.
        val picked = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        onValueChange(picked?.toString().orEmpty())
    }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        persistAccess(context, picked)
        onValueChange(picked.toString())
    }

    PickerFieldChrome(
        display = display,
        icon = Icons.Filled.MusicNote,
        enabled = true,
        onTap = { choosing = true },
        labelSlot = labelSlot,
        colors = colors,
    )

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.sound_choose_a_sound)) },
            text = { Text(stringResource(R.string.sound_pick_one_of_the_device)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        choosing = false
                        ringtones.launch(ringtonePickerIntent(value, pickerTitle))
                    },
                ) { Text(stringResource(R.string.sound_device_sounds)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        choosing = false
                        files.launch(AUDIO_MIME_TYPES)
                    },
                ) { Text(stringResource(R.string.sound_audio_file)) }
            },
        )
    }
}

/**
 * Keeps [uri] readable beyond this Activity.
 *
 * A document picker's grant is transient: it dies with the task that asked for
 * it. The engine plays this sound days later, from a service, quite possibly
 * after a reboot — so without taking the grant persistently the workflow would
 * work once while the editor is open and then fail silently forever after.
 *
 * Providers that are not document providers reject the request; that only means
 * the uri was already durable (a media-store ringtone), so the failure is not
 * one to report.
 */
private fun persistAccess(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun ringtonePickerIntent(current: String, title: String): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
        // "Silent" is not a sound; leaving the node unconfigured says that already.
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current.takeIf { it.isNotBlank() }?.toUri())
    }

/**
 * What to call the chosen sound: its media-store title, else the file name its
 * provider reports, else the raw uri — an empty field would read as
 * unconfigured when it is merely unnameable.
 */
private fun soundName(context: Context, uri: String): String {
    if (uri.isBlank()) return ""
    val parsed = runCatching { uri.toUri() }.getOrNull()
    return parsed?.let { ringtoneTitle(context, it) ?: documentName(context, it) } ?: uri
}

/**
 * The title the media store keeps for a sound ("Argon", not "Argon.ogg"). Only
 * asked of the providers that keep one: for anything else `RingtoneManager`
 * falls back to the uri's last path segment, which for a document uri is an
 * encoded row id and worse than the file name.
 */
private fun ringtoneTitle(context: Context, uri: Uri): String? {
    if (uri.authority != MediaStore.AUTHORITY && uri.authority != SETTINGS_AUTHORITY) return null
    return runCatching {
        RingtoneManager.getRingtone(context, uri)?.getTitle(context)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** The display name a document provider reports, typically the file name. */
private fun documentName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * Audio of any kind. Some providers file an mp3 as `application/octet-stream`,
 * in which case it is reachable through the picker's "show all" browsing rather
 * than the filtered list — a stricter filter here would not help it.
 */
private val AUDIO_MIME_TYPES = arrayOf("audio/*")

/** Where a stringResource(R.string.sound_default_ringtone) style uri lives; not a `MediaStore` constant. */
private const val SETTINGS_AUTHORITY = "settings"
