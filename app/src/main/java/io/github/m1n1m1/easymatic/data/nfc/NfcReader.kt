package io.github.m1n1m1.easymatic.data.nfc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Bundle
import androidx.core.content.IntentCompat
import io.github.m1n1m1.easymatic.domain.model.NfcTagId
import java.nio.charset.StandardCharsets

/** What one tap gave us. [uid] is blank only for a tag that reports no id at all. */
data class ScannedTag(
    val uid: String,
    val techs: List<String> = emptyList(),
    val text: String = "",
)

/**
 * Everything platform-specific about reading a tag, in one place.
 *
 * Two entry points into the same thing, because Android has two: an **intent**,
 * delivered to [io.github.m1n1m1.easymatic.data.trigger.NfcTagActivity] by the manifest
 * dispatch when a tag is tapped anywhere, and **reader mode**, which routes taps to
 * a resumed Activity instead. The capture overlay uses the second, and that choice
 * carries a property worth relying on: while reader mode is active the platform
 * performs no dispatch at all, so scanning a tag while setting a trigger up cannot
 * also fire the macro that watches it.
 */
@Suppress("TooManyFunctions") // Two dispatch routes, reader mode, and NDEF decoding.
object NfcReader {

    /** Whether this phone has an NFC chip. Distinct from it being switched on. */
    fun isAvailable(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)

    fun isEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    fun adapter(context: Context): NfcAdapter? =
        runCatching { NfcAdapter.getDefaultAdapter(context.applicationContext) }.getOrNull()

    /**
     * Whether the user has switched tag intents off for Easymatic specifically
     * (API 36+), which stops the manifest dispatch dead while leaving everything
     * else about NFC working.
     *
     * A fourth way for a tag trigger to look armed and never fire, and the one with
     * no visible symptom anywhere — hence a plain question the trigger can ask and
     * report. Answers true on every platform that has no such setting.
     */
    @Suppress("ReturnCount") // Two "nothing to report" guards ahead of the real answer.
    fun tagIntentsAllowed(context: Context): Boolean {
        // The setting itself has been on the phone since API 34, but both halves of
        // the question — `isTagIntentAppPreferenceSupported` and `isTagIntentAllowed`
        // — were `@FlaggedApi` until 36 and are public API only from there. Guarding
        // on anything lower would be reaching past the SDK for a method the platform
        // was free to move, so below 36 the honest answer is "as far as anything here
        // can tell, yes".
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return true
        val adapter = adapter(context) ?: return true
        return runCatching {
            !adapter.isTagIntentAppPreferenceSupported || adapter.isTagIntentAllowed
        }.getOrDefault(true)
    }

    /**
     * The tag behind a dispatch intent, or null when the intent is not one.
     *
     * Reads [NfcAdapter.EXTRA_ID] — a plain `ByteArray` — rather than `EXTRA_TAG`,
     * which is where everybody reaches first. The id is all the trigger matches on,
     * and taking it this way sidesteps the `Parcelable` extra entirely.
     */
    fun fromIntent(intent: Intent?): ScannedTag? {
        val id = intent?.getByteArrayExtra(NfcAdapter.EXTRA_ID) ?: return null
        return ScannedTag(uid = NfcTagId.format(id), text = textOf(messagesIn(intent)))
    }

    /**
     * The tag behind a reader-mode callback.
     *
     * [Ndef.getCachedNdefMessage] is the message the platform already read at
     * discovery, so this performs no I/O and needs no `connect()` — which matters
     * because it runs on the binder thread the callback arrives on, with the tag
     * possibly about to be moved away.
     */
    fun fromTag(tag: Tag): ScannedTag = ScannedTag(
        uid = NfcTagId.format(tag.id),
        techs = tag.techList.orEmpty().toList(),
        text = runCatching { Ndef.get(tag)?.cachedNdefMessage }.getOrNull()?.let { textOf(listOf(it)) }.orEmpty(),
    )

    /**
     * Routes taps to [activity] instead of to the manifest dispatch, until
     * [disableReaderMode].
     *
     * [NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK] is deliberately **not** set: with it
     * the platform hands over a tag it has not read, `Ndef.get` answers null, and
     * both the content preview and the whole write flow stop working.
     */
    fun enableReaderMode(activity: Activity, onTag: (Tag) -> Unit): Boolean {
        val adapter = adapter(activity) ?: return false
        val extras = Bundle().apply {
            // The default presence check polls hard enough to lose a tag mid-write.
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MS)
        }
        return runCatching { adapter.enableReaderMode(activity, onTag, READER_FLAGS, extras) }.isSuccess
    }

    /**
     * Hands tag dispatch back to the platform.
     *
     * Not optional: the registration outlives the overlay that made it, so a missed
     * call here leaves the app silently swallowing every tag tap — including the
     * ones its own triggers are waiting for — for the rest of the process.
     */
    fun disableReaderMode(activity: Activity) {
        runCatching { adapter(activity)?.disableReaderMode(activity) }
    }

    /**
     * Writes one record to [tag], reporting what went wrong in words the user can
     * act on.
     *
     * Every check that *can* be made before `connect()` is, because the failures
     * are otherwise indistinguishable: a message too big for the tag and a tag
     * pulled away mid-write both surface as a bare `IOException`, and "try holding
     * it still" is useless advice for a sticker that was never large enough.
     *
     * Must run on the thread the reader-mode callback arrives on, while the tag is
     * still in the field: a [Tag] handle is only valid until it leaves.
     */
    @Suppress("ReturnCount") // Each guard names a different failure; one exit would merge them.
    fun write(tag: Tag, record: NdefRecord): Result<Unit> {
        val message = NdefMessage(arrayOf(record))
        val size = message.toByteArray().size
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            if (!ndef.isWritable) return failure("This tag is locked and can no longer be written to.")
            if (ndef.maxSize < size) {
                return failure("This tag holds ${ndef.maxSize} bytes and that needs $size.")
            }
            return runCatching {
                ndef.use {
                    it.connect()
                    it.writeNdefMessage(message)
                }
            }.recoverCatching { throw writeFailure(it) }
        }
        val formatable = NdefFormatable.get(tag)
            ?: return failure("This tag cannot store data at all — it is not an NDEF tag.")
        return runCatching {
            formatable.use {
                it.connect()
                it.format(message)
            }
        }.recoverCatching { throw writeFailure(it) }
    }

    private fun failure(message: String): Result<Unit> = Result.failure(IllegalStateException(message))

    private fun writeFailure(cause: Throwable): Throwable = when (cause) {
        is TagLostException ->
            IllegalStateException("The tag moved away before the write finished. Hold it still and try again.")
        else -> IllegalStateException("Could not write to this tag: ${cause.message}")
    }

    private fun messagesIn(intent: Intent): List<NdefMessage> =
        IntentCompat.getParcelableArrayExtra(intent, NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
            .orEmpty()
            .filterIsInstance<NdefMessage>()

    /** The first record that has a text form, or blank. */
    private fun textOf(messages: List<NdefMessage>): String =
        messages.asSequence()
            .flatMap { it.records.orEmpty().asSequence() }
            .mapNotNull { textOf(it) }
            .firstOrNull()
            .orEmpty()

    /**
     * One record as text: a Text record decoded, a URI record as its URI.
     *
     * The status byte is the part that is easy to get wrong. Bit 7 selects the
     * encoding, and the language-code length is the **low six** bits — masking
     * `0x7F` instead of `0x3F` reads bit 6, which is reserved, and yields a length
     * that slices the text in the wrong place on any tag that happens to set it.
     *
     * URIs go through [NdefRecord.toUri], which knows the 36-entry prefix
     * abbreviation table and handles absolute-URI and smart-poster records too.
     */
    @Suppress("ReturnCount") // A record is one of three shapes; a single exit only nests them.
    private fun textOf(record: NdefRecord): String? {
        if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
            val payload = record.payload
            if (payload.isEmpty()) return null
            val status = payload[0].toInt() and BYTE_MASK
            val charset =
                if (status and UTF_16_FLAG == 0) StandardCharsets.UTF_8 else StandardCharsets.UTF_16
            val languageLength = status and LANGUAGE_LENGTH_MASK
            if (payload.size <= 1 + languageLength) return null
            return String(payload, 1 + languageLength, payload.size - 1 - languageLength, charset)
        }
        return runCatching { record.toUri()?.toString() }.getOrNull()
    }

    private const val BYTE_MASK = 0xFF
    private const val UTF_16_FLAG = 0x80
    private const val LANGUAGE_LENGTH_MASK = 0x3F
    private const val PRESENCE_CHECK_DELAY_MS = 500

    private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V
}
