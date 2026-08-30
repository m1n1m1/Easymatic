package io.github.m1n1m1.easymatic.feature.mail

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The mail account library, for the config form's Account picker.
 *
 * A [staticCompositionLocalOf] for the reason the geofence, variable and tag
 * libraries have one: the picker sits several composables inside a generic config
 * form that knows nothing about mail, so threading a ViewModel down to it would
 * mean a parameter on every layer in between.
 *
 * The `identifier-pickers` rule is that the app list and the macro list get no
 * local, "because those locals exist so a picker can *edit* its library and see
 * its own in-flight edits, and neither of these is editable from a config form".
 * This one **is**: adding an account from inside `action.send_mail`'s picker is how
 * anybody will create their first one. Same test, opposite answer.
 *
 * Null when no provider is in scope — a preview, a test — and the picker then falls
 * back to showing the raw id rather than crashing.
 */
val LocalMailAccounts = staticCompositionLocalOf<MailAccountsViewModel?> { null }
