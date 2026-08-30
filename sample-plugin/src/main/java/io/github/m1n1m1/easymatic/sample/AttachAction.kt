package io.github.m1n1m1.easymatic.sample

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.nodeapi.wire.LogLevelWire
import java.util.concurrent.atomic.AtomicBoolean
import io.github.m1n1m1.easymatic.domain.model.config.IntentChoice
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.plugin.PluginAction
import io.github.m1n1m1.easymatic.plugin.PluginContext
import io.github.m1n1m1.easymatic.plugin.PluginOutput
import io.github.m1n1m1.easymatic.plugin.pluginActionNode
import io.github.m1n1m1.easymatic.plugin.routes
import kotlinx.serialization.Serializable

/**
 * Config for the sample's third action — the one whose fields another app fills in.
 *
 * Both requests are answered by **Android itself**: the documents UI and the ringtone
 * chooser are system components, present on every phone, uninstallable by nobody and
 * needing no permission at either end. That is deliberate for a sample, because a sample is
 * what gets copied — a QR scanner or a camera would have shown the same mechanism while
 * teaching a field that is dead on any phone without the app behind it.
 *
 * The two fields are the two *different shapes of answer*, which is the thing worth reading
 * before copying either:
 *
 *  - [chime] comes back as a **value the plugin can act on with nothing else**. The ringtone
 *    chooser answers in an *extra* rather than in the result's data, which is what
 *    `resultExtra` is for, and the URI it hands over is playable in this process without a
 *    grant, a permission or anything lent — nothing crosses a process boundary but
 *    characters, and the sound still plays.
 *  - [document] comes back as a **handle** — a `content://` document Easymatic holds a grant
 *    on and this process does not. The string arrives like any other config value, and the
 *    *grant* is lent beside it for the length of the call (`PluginChannel.lend`). That is
 *    why [execute] asks the provider about it rather than merely reporting the URI: reaching
 *    through the URI is the only thing that actually proves the lend happened, and it is the
 *    one part of this mechanism a declaration test cannot check.
 *
 * A **document rather than a picture**, and that is not squeamishness. `ACTION_OPEN_DOCUMENT`
 * conveys read access to the chosen file as the whole point of the action, so there is no
 * media permission anywhere in this and nothing that behaves differently on one Android
 * version than another — where a picture invites `READ_MEDIA_IMAGES`, the photo picker's
 * process-lifetime grant, and a MediaStore row that is a different kind of handle again.
 * A sample should demonstrate the mechanism, not a second subject with rules of its own.
 *
 * Both are **editable**, like every `@IntentChoice` field. That is not a concession — the
 * interesting document is often one a macro built upstream, which is why [document] is
 * `@Wired`.
 *
 * `OPEN_DOCUMENT` rather than `GET_CONTENT`, and the choice is load-bearing rather than
 * stylistic: `GET_CONTENT`'s grant dies with the editor's task, so the macro would work once
 * and then fail silently forever. `OPEN_DOCUMENT`'s is persistable, and the host takes it.
 */
@Serializable
data class AttachConfig(
    @Label("Document")
    @Wired
    @IntentChoice(
        action = "android.intent.action.OPEN_DOCUMENT",
        mimeType = "*/*",
        category = "android.intent.category.OPENABLE",
        icon = NodeIcon.FILE,
    )
    val document: String = "",

    /**
     * The sound to play once the document has been opened.
     *
     * **Blank is a real answer** meaning no sound, on `PickerKind.NFC_TAG`'s reasoning: a
     * node that chimes is a choice and a node that does not is the default, so nothing warns
     * about an empty field.
     */
    @Label("Sound when done (plays once, up to 3 seconds)")
    @IntentChoice(
        action = "android.intent.action.RINGTONE_PICKER",
        resultExtra = "android.intent.extra.ringtone.PICKED_URI",
        icon = NodeIcon.MUSIC,
    )
    val chime: String = "",
)

/**
 * What the node hands downstream once it has looked at the document.
 *
 * Both fields are things the node **learned about the document**, which is the rule an
 * output port should follow and the first version of this node broke. That one answered a
 * bare `bytes`, which existed only to prove the grant had crossed — a debugging artifact on
 * a public port, where nothing about the node said whether it meant a size, a count or a
 * progress figure. If a value is only meaningful to whoever is debugging the mechanism, it
 * belongs in the run log and not on the card.
 *
 * [name] is also what makes a wrong answer diagnosable: a size on its own cannot tell you
 * that the wrong file was read, and a name can.
 */
@Serializable
data class Attached(
    /** The document's display name, as its own provider reports it. */
    val name: String,
    /**
     * How many bytes the provider says it holds, or **-1** when it will not say.
     *
     * `OpenableColumns.SIZE` is nullable and cloud providers routinely omit it. -1 rather
     * than 0, on the rule `FileResultItem` already follows: zero is a lie a macro acts on,
     * because it reads an unmeasured document as an empty one.
     */
    val size: Long,
)

/**
 * An action whose two config fields are filled in by other apps on the phone.
 *
 * The worked example for `@IntentChoice`, and the thing to copy from it is what the plugin
 * did **not** have to write: no Activity, no `startActivityForResult`, no result parsing, no
 * grant handling, and no `<queries>` entry. The declaration names an action and the host
 * does the rest — which is the whole point, because the alternative a plugin had before this
 * was a text box asking somebody to type a document's URI.
 *
 * Note also what it is **not** given. Nothing here reaches a geofence, a variable, a macro
 * or a mailbox of the user's; that is precisely why this is the one chooser a plugin may
 * declare. The single capability that does cross — read access to the one file chosen for
 * [AttachConfig.document] — is lent for this call and taken back after it, so the read below
 * has to happen *here*, in `execute`, and a URI kept for later is dead.
 *
 * **Every field does what its label says, and two rounds of review were needed to get there.**
 * The first version output a bare `bytes` that existed only to prove the grant had crossed —
 * a debugging artifact on a public port. The second still had a field labelled *Sound when
 * done* that nothing ever played. Both are the same fault: a node built to demonstrate a
 * *declaration*, whose behaviour was never written to match it. That is the shape of
 * "configured perfectly, does nothing", and a sample is the last place it belongs, because a
 * sample is what gets copied. See [Attached] and [playChime].
 */
class AttachAction : PluginAction<AttachConfig, Attached> {

    override val definition = pluginActionNode<AttachConfig, Attached>(
        typeId = "attach",
        displayName = "Attach a document",
        description = "Reads a chosen document and an alert sound — a worked example of @IntentChoice",
        icon = NodeIcon.SEND,
        output = dataOut<Attached>("attached", label = "Attached"),
        execOutputs = routes(
            // `out` first, as always: the host lands anything it cannot route honestly on
            // the first route, so the first one has to mean *carried on*.
            "out" to "When attached",
            "error" to "When it cannot be read",
        ),
    )

    override suspend fun execute(config: AttachConfig, context: PluginContext): PluginOutput<Attached> {
        val attached = describe(config.document, context)
            ?: return PluginOutput.failed("error")
        // Named first, because a name is what tells somebody reading this log that the run
        // opened the document they meant. A size on its own cannot say that.
        context.log("Opened '${attached.name}' (${attached.size} bytes)")
        playChime(config.chime, context)
        return PluginOutput(attached)
    }

    /**
     * Plays the chosen sound once, for at most [MAX_CHIME_MS], if one was chosen.
     *
     * **The field said "Sound when done" and nothing played it**, which is the same fault
     * `bytes` had and a worse instance of it: a label that names a behaviour is a promise,
     * and a promise a node does not keep is exactly the "configured perfectly, does nothing"
     * failure the plugin doctrine exists to refuse.
     *
     * ## Why it is not `RingtoneManager`, which is what it was
     *
     * `Ringtone.play()` looked like the obvious player for a URI that came out of the
     * ringtone chooser, and it **repeats until something stops it**. That is not a bug in it:
     * a phone ringtone is meant to ring until the call is answered, so a `Ringtone` built
     * from one plays under `USAGE_NOTIFICATION_RINGTONE` and loops by design. Nothing here
     * was ever going to stop it, so choosing a ringtone rather than a notification tone gave
     * a sound that never ended.
     *
     * The host already knew. `PlaySoundAction`'s cap exists in so many words because *"the
     * sounds that need a cap most — a ringtone, an alarm — are the ones that never stop on
     * their own"*, and `AndroidSystemServices.playDetached` is the shape mirrored here: a
     * teardown that runs **at most once**, reached by whichever of three things happens
     * first — the sound ending, an error, or the cap expiring. Silencing an already-silent
     * sound is then a no-op, so the three cannot race.
     *
     * Two further corrections come with it. The attributes are `USAGE_NOTIFICATION`, so the
     * sound behaves as a cue rather than as an incoming call however it was categorised; and
     * `prepareAsync` rather than `prepare`, because preparing reads the file and this runs on
     * a binder thread the host is waiting on.
     *
     * It costs **no permission** — `action.play_sound` declares none either. A sound the
     * plugin cannot open is reported rather than swallowed, because a silent chime is
     * indistinguishable from one that was never configured.
     */
    private fun playChime(spec: String, context: PluginContext) {
        // Blank means no sound, which is the default and not a misconfiguration.
        if (spec.isBlank()) return
        val silence = runCatching { Uri.parse(spec) }.getOrNull()?.let { startChime(context, it) }
        if (silence == null) {
            context.log("The chosen sound could not be opened, so nothing played", LogLevelWire.WARN)
            return
        }
        // Posted from here rather than from the prepared listener, so a preparation that
        // never finishes still releases the player. The cap therefore covers preparing as
        // well as playing, which is the safer reading of "at most three seconds".
        Handler(Looper.getMainLooper()).postDelayed({ silence() }, MAX_CHIME_MS)
    }

    /**
     * Starts [uri] playing once, and answers **how to silence it** — or null if it would not
     * start, in which case nothing is left holding a player.
     *
     * Handing the teardown back rather than keeping it is `AndroidSystemServices.register`'s
     * shape: the caller is the only thing that knows when the cap expires, and the listeners
     * are the only things that know when the sound ended, so the one operation both need has
     * to be a value they can share.
     */
    private fun startChime(context: PluginContext, uri: Uri): (() -> Unit)? {
        val player = MediaPlayer()
        val silenced = AtomicBoolean(false)
        val silence = {
            // compareAndSet, so the completion listener, the error listener and the cap can
            // all fire and only the first one does anything.
            if (silenced.compareAndSet(false, true)) {
                runCatching { if (player.isPlaying) player.stop() }
                player.release()
            }
        }
        return runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            // Explicit, though it is already the default: this is the exact property that
            // made the first version repeat, so it is worth being unmissable.
            player.isLooping = false
            player.setDataSource(context.android, uri)
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener { silence() }
            player.setOnErrorListener { _, _, _ ->
                silence()
                true
            }
            player.prepareAsync()
            silence
        }.getOrElse {
            silence()
            null
        }
    }

    /**
     * What the chosen document is, or null with a sentence in the run log saying why not.
     *
     * **Two calls, because they prove different things**, and the sample is here to prove
     * them. The query is what obtains the name and size, and it needs the lent grant — a
     * plugin without one gets a `SecurityException` on a URI that reads as perfectly correct
     * in the form, which is exactly the failure that would otherwise be invisible. Opening
     * the stream then proves the provider will actually *hand bytes over*, which a query
     * alone does not: a row can describe a document that cannot be read.
     *
     * **One byte is read and no more.** The first version of this node read the whole
     * document with `readBytes()` to report its length, which is the wrong thing for a
     * sample to teach twice over: it loads an arbitrary file into memory inside somebody's
     * plugin — the hazard `FileLimits.MAX_READ_BYTES` exists for on the host side — and it
     * measures the size a second time when the provider has already been asked. The size
     * comes from the provider; the stream is opened only to confirm it opens.
     */
    private fun describe(spec: String, context: PluginContext): Attached? {
        val uri = spec.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri == null) {
            context.log(
                if (spec.isBlank()) {
                    "No document chosen, so there is nothing to read"
                } else {
                    "'$spec' is not something this plugin can open"
                },
            )
            return null
        }
        return runCatching {
            val described = describeRow(context, uri)
            // Opened and immediately closed. `read()` answering -1 is an empty document,
            // which is fine; a provider that refuses throws, which is not.
            context.android.contentResolver.openInputStream(uri)?.use { it.read() }
                ?: error("the provider handed back no stream")
            described
        }.getOrElse { cause ->
            context.log("Could not read the document: ${cause.message}")
            null
        }
    }

    /**
     * The document's own name and size, straight from its provider.
     *
     * Both columns are **nullable** and cloud providers routinely omit them, so a missing
     * name falls back to the URI's last segment and a missing size answers -1. -1 rather
     * than 0 for the reason `FileResultItem` records: zero is a lie a macro acts on.
     */
    private fun describeRow(context: PluginContext, uri: Uri): Attached =
        context.android.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                Attached(
                    name = cursor.getString(0) ?: uri.lastPathSegment.orEmpty(),
                    size = if (cursor.isNull(1)) UNKNOWN_SIZE else cursor.getLong(1),
                )
            }
            ?: Attached(name = uri.lastPathSegment.orEmpty(), size = UNKNOWN_SIZE)

    private companion object {
        /** What a size means when the provider will not report one. Never 0. */
        const val UNKNOWN_SIZE = -1L

        /**
         * How long a chime may make noise for.
         *
         * A cue, not a ringtone: three seconds is long enough to be heard and short enough
         * that a sound with no natural end — which is most ringtones and every alarm — cannot
         * keep a macro's completion audible for minutes. The host caps for the same reason
         * and lets the user choose the number; a sample takes the sensible one.
         */
        const val MAX_CHIME_MS = 3_000L
    }
}
