package io.github.m1n1m1.easymatic.feature.intent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResult
import androidx.core.content.IntentCompat
import io.github.m1n1m1.easymatic.core.service.WhenExists
import io.github.m1n1m1.easymatic.data.images.MediaWrites
import io.github.m1n1m1.easymatic.domain.registry.ConfigFieldType

/**
 * The one place an `@IntentChoice` declaration becomes a launch, and a launch's answer
 * becomes the string a config field stores.
 *
 * Split out from [IntentChoiceField] because none of it is Compose and all of it is the part
 * worth testing: the composable is a text field and a button, while everything that can be
 * *wrong* — which extra the answer is in, whether a cancelled result means "keep what you
 * had", what happens to a destination nobody wrote to — lives here and is reachable from
 * plain JUnit with a Robolectric-free `Intent`.
 *
 * It is deliberately not a table of known actions. The declaration says what to launch and
 * this builds exactly that; see `@IntentChoice` for what the open shape costs.
 */
internal object IntentRequests {

    /**
     * The launch this field asks for, with [outputUri] filled in when the declaration asked
     * for a destination.
     *
     * Implicit by construction: there is no component and no package, so `PackageManager`
     * decides who answers. That is the whole of the trust argument for letting a plugin
     * declare one of these, so it is not a detail to optimise away later by "resolving the
     * best match first and launching that".
     */
    fun intentFor(type: ConfigFieldType.INTENT_CHOICE, outputUri: Uri? = null): Intent =
        Intent(type.action).apply {
            if (type.mimeType.isNotBlank()) {
                this.type = type.mimeType
                // The document actions read EXTRA_MIME_TYPES and ignore setType's filter when
                // it is present, so both are set: without the extra, "image/*" narrows nothing
                // in DocumentsUI and the chooser offers every file on the phone.
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(type.mimeType))
            }
            if (type.category.isNotBlank()) addCategory(type.category)
            extrasOf(type.inputExtras).forEach { (key, value) -> putExtra(key, value) }
            if (outputUri != null && type.outputExtra.isNotBlank()) {
                putExtra(type.outputExtra, outputUri)
                // The app being asked has to be able to write where we told it to. This is a
                // grant on a row *we* created, conveyed by the launch itself and expiring
                // with the task — not a persisted one, and not on anything of the user's.
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

    /**
     * The answer, or null when there is none.
     *
     * Null means **keep what the field already held**, which is what a back gesture out of a
     * gallery has to mean: a chooser that cleared the field on dismissal would make "let me
     * just look" a destructive act — `finishWithoutChoosing`'s rule, reached by a second
     * route.
     *
     * Three sources in order, and the order is the declaration's:
     *
     *  1. [ConfigFieldType.INTENT_CHOICE.resultExtra] — the ringtone chooser answers in
     *     `EXTRA_RINGTONE_PICKED_URI`. Read as a string first and as a [Uri] second, because
     *     both spellings are in the wild for the same idea, and that one is a `Uri`.
     *  2. the result `Intent`'s own `data`, which is what every document and pick action
     *     answers with.
     *  3. [outputUri] — the destination we supplied. A camera app answers `RESULT_OK` with
     *     no data at all, because the picture is where we said to put it.
     *
     * The `runCatching` is not padding: reading *any* extra unparcels the whole bundle, so
     * an app answering with a Parcelable of its own throws `BadParcelableException` here, in
     * this process, for a class this process has never heard of. `chosenValueFrom` learned
     * that at the plugin boundary; the same hazard applies to every app on the phone.
     */
    fun answerFrom(
        type: ConfigFieldType.INTENT_CHOICE,
        result: ActivityResult,
        outputUri: Uri? = null,
    ): String? {
        if (result.resultCode != Activity.RESULT_OK) return null
        val data = result.data
        val fromExtra = type.resultExtra
            .takeIf { it.isNotBlank() }
            ?.let { key ->
                runCatching {
                    data?.getStringExtra(key)
                        ?: data?.let { IntentCompat.getParcelableExtra(it, key, Uri::class.java) }?.toString()
                }.getOrNull()
            }
        return answerOf(
            fromExtra = fromExtra,
            fromData = data?.data?.toString(),
            fromOutput = outputUri?.toString(),
        )
    }

    /**
     * The `key=value` pairs to put on the launch, from the declaration's raw entries.
     *
     * Pure and `internal` so it is JVM-testable: this is one of the two places an
     * `@IntentChoice` can be silently wrong in a way nobody could diagnose from the phone,
     * and the other is [answerOf]. Everything around them needs an `Intent`, which is an
     * `android.jar` stub under plain JUnit — `PluginChannel`'s reason for existing, applied
     * to the smaller problem.
     *
     * `substringBefore`/`After` rather than `split('=')`: a value may legitimately contain
     * `=` — a base64 pad, a query string — and only the **first** one separates.
     *
     * Two entries are dropped rather than sent, and the `contains` guard is what separates
     * them: an entry with a blank key, and one with no `=` at all — for which
     * `substringBefore` answers the *whole string*, so without the guard `SCAN_MODE` would
     * go on as `SCAN_MODE=""`. Both would arrive on the far side indistinguishable from an
     * extra that was never sent, so they are dropped here, where the two validators that
     * refuse them at declaration time can be seen to be the real answer.
     */
    fun extrasOf(entries: List<String>): List<Pair<String, String>> = entries.mapNotNull { entry ->
        if (!entry.contains('=')) return@mapNotNull null
        val key = entry.substringBefore('=')
        key.takeIf { it.isNotBlank() }?.let { it to entry.substringAfter('=', "") }
    }

    /**
     * Which of the three possible answers a launch actually produced, in the declaration's
     * order of preference.
     *
     * Pure, for [extrasOf]'s reason, and the order is the whole content of it: the extra the
     * declaration named beats the result `Intent`'s own `data`, which beats the destination
     * the host supplied. That last fallback is what makes a camera work at all — an app that
     * wrote where it was told answers `RESULT_OK` with no data whatsoever.
     *
     * A blank at any level is **not** an answer and falls through, because an app that
     * answers `RESULT_OK` with an empty extra has told us nothing, and storing the empty
     * string would clear a field the user had already filled in.
     */
    fun answerOf(fromExtra: String?, fromData: String?, fromOutput: String?): String? =
        listOfNotNull(fromExtra, fromData, fromOutput).firstOrNull { it.isNotBlank() }

    /**
     * A row for the app being asked to write into, when the declaration named an
     * [ConfigFieldType.INTENT_CHOICE.outputExtra].
     *
     * `MediaWrites` rather than a `FileProvider`, and the reason is the one that file already
     * records: a row this app **creates** is a row it owns, so writing it needs no consent on
     * any Android version and reading it back later needs no grant. A `FileProvider` would
     * have meant a new provider in the manifest and a path config, to arrive at a handle that
     * is worse — `ImageRef` reads a `content://` media row and `Images.encodeForModel` finds
     * it without touching the file router.
     *
     * Created **pending**, and published only once the app reports success, so a gallery
     * never shows the zero-byte row for a photograph somebody backed out of.
     */
    fun createOutput(context: Context, mimeType: String): MediaWrites.Target? = MediaWrites.create(
        context = context,
        folder = MediaWrites.DEFAULT_FOLDER,
        name = "capture-${System.currentTimeMillis()}.${extensionFor(mimeType)}",
        mimeType = mimeType.ifBlank { "image/jpeg" },
        whenExists = WhenExists.KEEP_BOTH,
    )

    /**
     * What to call a stored value on screen.
     *
     * A `content://` row id is not a name, and a field showing one reads as broken even when
     * it is correct — the failure `SoundPickerField` resolves with the identical query and
     * `PhoneRef` avoids by caching the name inside the spec. Anything that is not a
     * `content://` URI is already legible (a path, a scanned code, a URL) and is its own
     * label, so this answers null and the field shows the value itself.
     */
    fun displayName(context: Context, value: String): String? = value
        .takeIf { it.startsWith(CONTENT_SCHEME) }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?.let { uri ->
            runCatching {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
        }
        ?.takeIf { it.isNotBlank() }

    /**
     * Keeps a chosen `content://` value readable beyond the editor's task.
     *
     * The engine opens this days later, from a foreground service, quite possibly after a
     * reboot — so without the persistable grant a macro would work once while the editor is
     * open and then fail silently forever, which is precisely what `SoundPickerField` records
     * and what makes `ACTION_OPEN_DOCUMENT` the action to reach for.
     *
     * The failure is **not** reported, because it has two causes that look identical from
     * here and only one of them is a problem: a MediaStore or settings URI is already durable
     * and rejects the request, while an `ACTION_GET_CONTENT` grant is transient and cannot be
     * made durable by anyone. The declaration is where that is decided; see `@IntentChoice`.
     */
    fun persist(context: Context, value: String) {
        if (!value.startsWith(CONTENT_SCHEME)) return
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** The file extension a captured picture's name gets, from the mime type it was asked for. */
    private fun extensionFor(mimeType: String): String = when (mimeType.substringAfterLast('/')) {
        "png" -> "png"
        "webp" -> "webp"
        else -> "jpg"
    }

    private const val CONTENT_SCHEME = "content://"
}
