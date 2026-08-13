package com.example.ottomatic.feature.contacts

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A `@ContactName` field: a person's name the user can type, with a button that fills
 * it in from the device's contacts.
 *
 * [PhoneNumberField]'s shape with one deliberate difference, and it is the difference
 * that makes this a separate field rather than a flag on that one: choosing somebody
 * here **does not lock the field**. There is no spec to corrupt — the text *is* the
 * value — so a name filled in from the address book can then be cut down to the part
 * that matters, which is exactly what a `contains` filter wants: pick "Anna
 * Müller-Schmidt", keep "Anna", and it still matches when a messenger prints the
 * name differently.
 *
 * It picks a **contact** rather than a phone row, which is the other difference:
 * a number is irrelevant here, and filtering the list down to people who have one
 * would hide contacts reachable only through a messenger.
 *
 * Needs no permission at all — neither here, where `ACTION_PICK` hands its row back
 * under a transient grant, nor later, since nothing is resolved when the trigger
 * fires.
 */
@Composable
fun ContactNameField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    val context = LocalContext.current

    // Launched into the composition's own scope rather than parked in a state a
    // `LaunchedEffect` keys on — see [PhoneNumberField], where the tidier-looking
    // version cancelled its own read and left the field blank.
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val name = withContext(Dispatchers.IO) { readContactName(context, uri) } ?: return@launch
            onValueChange(name)
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = labelSlot,
        colors = colors,
        placeholder = { Text(text =
            stringResource(R.string.contacts_anyone), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            IconButton(onClick = { picker.launch(CONTACT_NAME_PICKER_INTENT) }) {
                Icon(imageVector = Icons.Filled.Person, contentDescription =
                    stringResource(R.string.contacts_fill_in_a_contact_s))
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Picks a *contact* rather than a phone row, unlike the number field's picker: what
 * is wanted here is a name, and listing only people with a number saved would hide
 * everybody reachable through a messenger and nowhere else.
 */
private val CONTACT_NAME_PICKER_INTENT =
    Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)

/**
 * The chosen contact's display name, or null when the row cannot be read.
 *
 * Readable with no `READ_CONTACTS`, on the transient grant `ACTION_PICK` attaches to
 * the row it returns. Unlike the number field nothing is stored to re-resolve later,
 * so the grant expiring costs nothing at all.
 */
private fun readContactName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        cursor.string(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY).takeIf { it.isNotBlank() }
    }
}.getOrNull()
