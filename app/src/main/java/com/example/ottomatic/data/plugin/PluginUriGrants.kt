package com.example.ottomatic.data.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The Android half of `PluginChannel.lend`: giving one plugin package read access to a file
 * the user picked for one of its nodes, and taking it back again.
 *
 * ## Why it exists
 *
 * An `@IntentChoice` field may hold a `content://` URI. The string crosses the binder like
 * any other config value, and a string is all the plugin gets — its process holds no grant,
 * so `openInputStream` answers `SecurityException` and a node that is configured correctly
 * never works, with nothing anywhere saying why. That is the silent failure the plugin
 * doctrine exists to refuse.
 *
 * ## Why it does not break the rule it looks like it breaks
 *
 * `nodeapi/wire` forbids a `Uri`, a `PendingIntent`, an `IBinder` or a
 * `ParcelFileDescriptor` from crossing, and nothing here goes near the wire. The forbidden
 * direction is a **plugin handing the host a capability the host then exercises** — a URI
 * arriving with `FLAG_GRANT_READ_URI_PERMISSION` on it, borrowing Ottomatic's own grants.
 * This is the opposite direction and a different act: the host lending, by name and out of
 * band, access to one file for one call.
 *
 * Three bounds keep it that way, and none is optional. Which values are lendable is decided
 * by `intentChoiceUris` from the node's **declared schema**, so a plugin cannot widen it by
 * putting a URI in a text field. The lend is undone in the caller's `finally`, so a plugin
 * that keeps the URI finds it dead. And `grantUriPermission` cannot manufacture access —
 * a URI Ottomatic itself has no grant on throws, and the plugin is left exactly as unable
 * to read it as before.
 */
internal object PluginUriGrants {

    /** Lends read access to every parseable entry of [uris]. */
    fun grant(context: Context, packageName: String, uris: List<String>) {
        forEachUri(uris) { uri ->
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Gives back what [grant] lent. */
    fun revoke(context: Context, packageName: String, uris: List<String>) {
        forEachUri(uris) { uri ->
            context.revokeUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Runs [action] for every entry that parses, swallowing each failure on its own.
     *
     * Per-URI rather than around the loop, because the two calls have opposite failure
     * modes and both must be survivable: a grant fails when this app does not hold one to
     * lend, and a revoke fails when there was nothing to take back. Neither is worth
     * reporting — the plugin's own read is where an unreadable file gets a sentence — and
     * neither may stop the other URIs in the same call being dealt with.
     */
    private inline fun forEachUri(uris: List<String>, action: (Uri) -> Unit) {
        uris.forEach { value ->
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return@forEach
            runCatching { action(uri) }
        }
    }
}
