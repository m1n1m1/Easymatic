package io.github.m1n1m1.easymatic.feature.contacts

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import io.github.m1n1m1.easymatic.domain.model.PhoneRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A `@PhoneNumber` field: a number the user can type, with a button that fills it in
 * from the device's contacts.
 *
 * Editable, unlike a `@Picker`, because a number that is in nobody's address book
 * has nothing to pick from — a read-only field would make it unreachable. This is
 * `DateTimeField`'s shape, with one deliberate difference: when a **contact** is
 * chosen the field goes read-only and shows the person's name. A date's text *is*
 * its value, so editing it means something; a contact's display text is a cached
 * name that is not its value at all, so a keystroke into it would corrupt the spec.
 * The ✕ is how you get back to a typeable field.
 *
 * Choosing a contact needs no permission — the system picker hands its row back
 * under a transient grant. Only *resolving* one when the node runs needs
 * `READ_CONTACTS`, which is why the node's config sheet grows a notice at that point
 * and not before.
 */
@Composable
fun PhoneNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    val context = LocalContext.current
    val ref = PhoneRef.parse(value)
    val contact = ref as? PhoneRef.Contact

    // The read is launched into the composition's own scope rather than parked in a
    // state a `LaunchedEffect` keys on. That shape looks tidier and does not work:
    // clearing the state to mark the pick as handled changes the effect's key, so
    // Compose cancels the very coroutine doing the read — reliably, because a
    // provider round trip on Dispatchers.IO takes longer than the frame the
    // recomposition lands on. The result was a picker that opened, accepted a
    // choice, and left the field blank.
    val scope = rememberCoroutineScope()

    // Previews provide no ActivityResultRegistryOwner; this field is only ever shown
    // inside the editor, which lives in a ComponentActivity.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            // Naming a contact is a content-provider query, and this callback runs
            // on the main thread.
            val picked = withContext(Dispatchers.IO) { readContact(context, uri) }
                ?: return@launch
            onValueChange(picked.spec())
        }
    }

    OutlinedTextField(
        value = contact?.displayName ?: (ref as? PhoneRef.Literal)?.number.orEmpty(),
        // A literal spec is the number itself, so typed text goes straight through
        // with no encoding round trip on every keystroke.
        onValueChange = { if (contact == null) onValueChange(it) },
        readOnly = contact != null,
        label = labelSlot,
        colors = colors,
        placeholder = { Text(text =
            stringResource(R.string.contacts_type_a_number), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        trailingIcon = {
            Row {
                if (contact != null) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.contacts_clear_the_contact_and_type),
                        )
                    }
                }
                IconButton(onClick = { picker.launch(CONTACT_PICKER_INTENT) }) {
                    Icon(imageVector = Icons.Filled.Person, contentDescription =
                        stringResource(R.string.contacts_choose_a_contact))
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Picks a *phone row* rather than a contact, which does two things at once: it lists
 * only people who actually have a number, and it lets the user see which number they
 * are pointing at when someone has several.
 */
private val CONTACT_PICKER_INTENT =
    Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

/** What the picker handed back: enough to store either kind of [PhoneRef]. */
private class PickedContact(val lookupKey: String, val name: String, val number: String) {

    /**
     * A reference when the row carried a lookup key, and the plain number when it
     * did not.
     *
     * The fallback is the point. A vendor contacts app that withholds the key — or
     * withholds it under the pick grant — must not leave the user staring at a field
     * that stayed blank after they chose somebody; the number they picked is right
     * there and is a perfectly good literal. They lose the "follows an edit in
     * Contacts" property, and they can see that they have, because the field shows a
     * number rather than a name.
     */
    fun spec(): String = when {
        lookupKey.isBlank() -> number
        else -> PhoneRef.contactSpec(lookupKey, name.ifBlank { number })
    }

    val isUsable: Boolean get() = lookupKey.isNotBlank() || number.isNotBlank()
}

/**
 * The row the picker returned, or null when it cannot be read at all.
 *
 * Readable with no `READ_CONTACTS`: `ACTION_PICK` grants read access to the one row
 * it hands back, for as long as this task lives. That is also why the key is stored
 * rather than re-queried later — the grant does not survive the task, and the cached
 * name is what lets the field still say "Mum" tomorrow.
 *
 * Columns are read by name rather than by index, because a provider is free to
 * return fewer than were asked for; a missing one reads as absent instead of
 * shifting every other column by one.
 */
private fun readContact(context: Context, uri: Uri): PickedContact? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        PickedContact(
            lookupKey = cursor.string(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY),
            // The number is the fallback name: a contact with no name at all would
            // otherwise show as an empty field, which reads as nothing chosen.
            name = cursor.string(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY),
            number = cursor.string(ContactsContract.CommonDataKinds.Phone.NUMBER),
        ).takeIf { it.isUsable }
    }
}.getOrNull()

/**
 * The named column's text, or blank when the provider did not return it.
 *
 * `internal` rather than private so [ContactNameField] reads its row the same way.
 * Copying four lines would be the alternative, and it is how one of the two ends up
 * reading a column by index after a refactor and shifting every other one by a place.
 */
internal fun Cursor.string(column: String): String {
    val index = getColumnIndex(column)
    return if (index < 0) "" else getString(index).orEmpty()
}
