package com.example.ottomatic.feature.intent

import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.ottomatic.R
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.data.images.MediaWrites
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.feature.grapheditor.nodeIcon
import com.example.ottomatic.feature.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An `@IntentChoice` field: a text field the user can type into, with a button that asks
 * another app on the phone for the answer.
 *
 * The sixth editable-with-a-chooser field, after `@PhoneNumber`, `@TimeOfDay`,
 * `@WifiNetwork`, `@ContactName` and `@FilePath` — and the first whose chooser is not
 * written here at all. What opens is whatever the declaration named and `PackageManager`
 * resolved: a gallery, a document provider, the camera, a barcode scanner.
 *
 * It stays **editable** on `@FilePath`'s argument. A chooser can only offer what exists
 * *now*, so the picture a previous run wrote is unreachable through one; the field is
 * routinely `@Wired`, and a read-only picker can never be; and what is stored — a path, a
 * scanned code, a URL — is legible enough to read back and see is wrong. The one thing that
 * is not legible is a `content://` row id, and that is what [supportingText] is for.
 *
 * ## Three failures it exists to make visible
 *
 * Each of these silently did nothing before there was somewhere to say so, and all three
 * look identical from the outside — a button that appears to be dead:
 *
 *  - **Nothing on this phone answers the action.** `startActivityForResult` with nothing to
 *    start reports `RESULT_CANCELED`, which is also what backing out of a gallery reports —
 *    so it is asked *before* the launch, never inferred after it.
 *  - **The camera refuses because this app has not been granted CAMERA.** See
 *    [Permissions.CAMERA]: Ottomatic declares it for the torch and never opens a camera, and
 *    that declaration alone is what makes `ACTION_IMAGE_CAPTURE` fail. This is the single
 *    place the field is not generic over its declaration, and the platform forces it.
 *  - **A destination nobody wrote to.** A capture that is cancelled leaves the pending row
 *    behind, which would surface in a gallery as a zero-byte photograph. It is abandoned.
 */
@Composable
fun IntentChoiceField(
    type: ConfigFieldType.INTENT_CHOICE,
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    val context = LocalContext.current

    // Whether any app answers this at all. Read once per declaration rather than per
    // recomposition: it is a PackageManager query, and the answer only changes when
    // something is installed or removed — which the editor is not open across.
    val answerable = remember(type, context) { IntentRequests.isAnswerable(context, type) }
    val needsCamera = remember(type) { type.action == MediaStore.ACTION_IMAGE_CAPTURE }
    val camera = rememberPermissionState(if (needsCamera) listOf(Permissions.CAMERA) else emptyList())

    // The row an app is being asked to write into, held across the launch so the answer can
    // fall back to it and so a cancelled capture can be cleaned up. `remember` rather than a
    // local, because the composable is recomposed while the other app is in front.
    var output by remember(type) { mutableStateOf<MediaWrites.Target?>(null) }

    // Naming a content:// value queries a provider, so it stays off the composition thread
    // and is redone only when the value changes — SoundPickerField's arrangement, and for
    // its reason.
    val name by produceState(initialValue = "", value, context) {
        this.value = withContext(Dispatchers.IO) { IntentRequests.displayName(context, value).orEmpty() }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = output
        output = null
        val answer = IntentRequests.answerFrom(type, result, target?.uri)
        if (answer == null) {
            // Nothing was chosen: the field keeps what it had, and a destination that was
            // created for a photograph nobody took is removed rather than left as an empty
            // row every gallery on the phone would show.
            if (target != null) MediaWrites.abandon(context, target)
            return@rememberLauncherForActivityResult
        }
        // Published only now, so the row was never visible while it was still empty.
        if (target != null) MediaWrites.publish(context, target)
        IntentRequests.persist(context, answer)
        onValueChange(answer)
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = labelSlot,
        colors = colors,
        placeholder = {
            Text(
                text = stringResource(R.string.intent_choice_placeholder),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        isError = !answerable,
        supportingText = supportingText(
            answerable = answerable,
            needsCameraGrant = needsCamera && !camera.allGranted,
            name = name,
        ),
        trailingIcon = {
            IconButton(
                enabled = answerable,
                onClick = {
                    when {
                        // Asked for, not merely reported: the grant is one tap away and the
                        // capture is unusable without it, so demanding the user find it in
                        // Settings would be a worse answer than the dialog.
                        needsCamera && !camera.allGranted -> camera.request()
                        else -> {
                            val target = type.outputExtra
                                .takeIf { it.isNotBlank() }
                                ?.let { IntentRequests.createOutput(context, type.mimeType) }
                            output = target
                            launcher.launch(IntentRequests.intentFor(type, target?.uri))
                        }
                    }
                },
            ) {
                Icon(
                    imageVector = nodeIcon(type.icon),
                    contentDescription = stringResource(R.string.intent_choice_ask_an_app),
                )
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * What sits under the field: why it cannot be used yet, or the name of what is in it.
 *
 * One line at a time, most-blocking first — a field nothing can fill has no name worth
 * showing, and one waiting on a grant has nothing in it yet either. Null when there is
 * nothing to say, so the row does not reserve height it never uses.
 *
 * [needsCameraGrant] earns its line because without it the button *does nothing visible* on
 * the first tap: it raises the system permission dialog, and the chooser then opens only on
 * the second tap. Auto-launching once the grant lands was the alternative and is worse — the
 * grant may be given minutes later in Settings, and a camera opening by itself on return to
 * the app is a surprise nobody asked for. So the field says what it is waiting for instead.
 */
@Composable
private fun supportingText(
    answerable: Boolean,
    needsCameraGrant: Boolean,
    name: String,
): (@Composable () -> Unit)? = when {
    !answerable -> {
        { Text(stringResource(R.string.intent_choice_no_app)) }
    }
    needsCameraGrant -> {
        { Text(stringResource(R.string.intent_choice_needs_camera)) }
    }
    name.isNotBlank() -> {
        { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    else -> null
}
