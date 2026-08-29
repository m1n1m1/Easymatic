package com.example.ottomatic.data.translate

import android.util.Log
import com.example.ottomatic.core.service.Translation
import com.example.ottomatic.core.service.TranslationOutcome
import com.example.ottomatic.core.service.TranslationRequest
import com.example.ottomatic.domain.registry.TranslateLanguages
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.google.mlkit.nl.translate.Translation as MlKitTranslator

private const val TAG = "MlKitTranslation"

/** ML Kit's own token for "I will not commit to a language". */
private const val UNDETERMINED = "und"

private const val NOTHING_TO_TRANSLATE = "There is nothing to translate"

private const val UNKNOWN_SOURCE = "The language of that text could not be worked out"

private const val TRANSLATION_FAILED = "The text could not be translated"

private const val DOWNLOAD_FAILED = "That language could not be downloaded"

private const val DELETE_FAILED = "That language could not be deleted"

/**
 * On-device translation through ML Kit.
 *
 * **One of the two files in the app that import `com.google.mlkit`**, the other being `MlKitAi`,
 * whose KDoc states the rule both follow: everything worth testing about translation — which
 * language a node decided to translate from, what a blank source means, where a failure lands — is
 * decided above this by `TranslateAction` against the [Translation] interface, with no device
 * present. What is left here is the call itself.
 *
 * **It needs no `Context`.** Neither `Translation.getClient` nor `LanguageIdentification.getClient`
 * takes one, and ML Kit initialises itself from its own manifest provider — `MlKitAi`'s
 * arrangement, so `ServiceLocator` constructs this with nothing. It also needs no Play services:
 * `com.google.mlkit:translate` is the bundled artifact, which carries its own inference runtime and
 * its own downloader, so there is no `DeviceCapability` behind the node.
 *
 * **Nothing here throws.** Every failure becomes a sentence, and [CancellationException] is
 * rethrown, on this project's standing rule: stopping a run must stop it rather than log a bogus
 * failure and walk on.
 *
 * ### Two interfaces, one object
 *
 * It is both [Translation] and [TranslationSetup], and they must be the same instance rather than
 * two: [delete] has to close the cached translators holding the model it is about to remove, and a
 * separate settings-side object would have no way to reach them. The *split* that matters is on the
 * interfaces, not on the objects — the engine is handed the half with no `download` on it.
 *
 * ### Two models, not one
 *
 * ML Kit pivots through English, so translating German to French needs the German **and** the
 * French model — English itself is built in. That is why [translate] checks both ends before it
 * starts, and why the models screen says so in as many words: a user who deleted French and
 * expected `de → fr` to keep working would otherwise have no way to find out why it stopped.
 */
internal class MlKitTranslation : Translation, TranslationSetup {

    private val cache = TranslatorCache()

    private val models = RemoteModelManager.getInstance()

    private val identifier by lazy { LanguageIdentification.getClient() }

    init {
        // A constant of the library rather than a question put to the phone, so it is published
        // once here and never revisited. See `TranslateLanguages`.
        TranslateLanguages.hydrateSupported(
            runCatching { TranslateLanguage.getAllLanguages().sorted() }.getOrDefault(emptyList()),
        )
    }

    override fun supported(): List<String> = TranslateLanguages.supported()

    @Suppress("ReturnCount") // Nothing to translate, no target, an unknown language, one
    // identification would not name, the same-language shortcut and a missing model — six
    // sentences, and collapsing them would make the commonest one unreadable.
    override suspend fun translate(request: TranslationRequest): TranslationOutcome {
        if (request.text.isBlank()) return TranslationOutcome(error = NOTHING_TO_TRANSLATE)

        val target = TranslateLanguage.fromLanguageTag(request.targetLanguage)
            ?: return TranslationOutcome(error = unsupported(request.targetLanguage))

        // Blank means work it out. A source that cannot be identified is reported rather than
        // guessed at: translating a two-word message from the wrong language would come back
        // looking like a successful translation.
        val sourceTag = request.sourceLanguage.ifBlank { identify(request.text) }
        if (sourceTag.isBlank()) return TranslationOutcome(error = UNKNOWN_SOURCE)
        val source = TranslateLanguage.fromLanguageTag(sourceTag)
            ?: return TranslationOutcome(error = unsupported(sourceTag))

        // Not a special case in the SDK — it would build a same-language client and want a model to
        // answer with its own input. Answering here means a macro whose text already arrived in the
        // target language works even with nothing downloaded at all.
        if (source == target) return TranslationOutcome(text = request.text, sourceLanguage = source)

        // Checked before translating rather than after failing, because ML Kit's own error for a
        // missing model says nothing about *which* language, and that is the entire content of the
        // answer somebody needs.
        val missing = missingModels(listOf(source, target))
        if (missing.isNotEmpty()) return TranslationOutcome(error = notDownloaded(missing))

        return runTranslation(source, target, request.text)
    }

    private suspend fun runTranslation(
        source: String,
        target: String,
        text: String,
    ): TranslationOutcome = try {
        val translated = cache.get(source, target).translate(text).await()
        TranslationOutcome(text = translated.orEmpty(), sourceLanguage = source)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failed: Throwable) {
        Log.w(TAG, "Translation failed", failed)
        TranslationOutcome(error = failed.message?.ifBlank { null } ?: TRANSLATION_FAILED)
    }

    /**
     * Works out what language [text] is in, as a BCP-47 tag, or `""`.
     *
     * Private rather than a facade member: its one caller is [translate] with a blank source, and a
     * public member answering a bare tag could not say which of three things a blank meant. See
     * [Translation]'s KDoc.
     *
     * **This one needs nothing downloaded.** The identification model ships inside the AAR, which is
     * why detection still works on a phone that has fetched no languages at all — it will then name
     * the language and [translate] will report that its model is missing, which is a far better
     * answer than refusing to look.
     */
    private suspend fun identify(text: String): String = try {
        // ML Kit answers the string "und" when it will not commit, which is the "declined to say"
        // that has to stay distinguishable from a confident answer.
        identifier.identifyLanguage(text).await()?.takeIf { it != UNDETERMINED }.orEmpty()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failed: Throwable) {
        Log.w(TAG, "Language identification failed", failed)
        ""
    }

    override suspend fun installed(): List<String> = try {
        downloadedTags()
            .sorted()
            // Republished on every read, which is how the node's pickers and `PickerOptions` learn
            // what the settings screen did. See `TranslateLanguages`.
            .also { TranslateLanguages.hydrateDownloaded(it) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failed: Throwable) {
        Log.w(TAG, "Downloaded translation models could not be listed", failed)
        emptyList()
    }

    override suspend fun download(language: String): String {
        val tag = TranslateLanguage.fromLanguageTag(language) ?: return unsupported(language)
        return try {
            // No `requireWifi`: somebody is watching a spinner having just asked for this, and a
            // condition that waits rather than fails would hang that spinner forever. See
            // `TranslationSetup`.
            val conditions = DownloadConditions.Builder().build()
            models.download(TranslateRemoteModel.Builder(tag).build(), conditions).await()
            ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failed: Throwable) {
            Log.w(TAG, "Translation model could not be downloaded", failed)
            failed.message?.ifBlank { null } ?: DOWNLOAD_FAILED
        }
    }

    override suspend fun delete(language: String): String {
        val tag = TranslateLanguage.fromLanguageTag(language) ?: return unsupported(language)
        return try {
            // Any cached translator for this language holds its model open, so they go first —
            // otherwise the delete succeeds and the next translation quietly uses the copy the open
            // client is still holding, which looks exactly like the delete having failed.
            cache.closeFor(tag)
            models.deleteDownloadedModel(TranslateRemoteModel.Builder(tag).build()).await()
            ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failed: Throwable) {
            Log.w(TAG, "Translation model could not be deleted", failed)
            failed.message?.ifBlank { null } ?: DELETE_FAILED
        }
    }

    /**
     * Which of [languages] have no model on this phone.
     *
     * Asked of ML Kit directly rather than of `TranslateLanguages`, because that registry is a cache
     * for the *editor* and this is the check a run depends on. Refusing a translation because a
     * stale list said so would be the worst of both.
     *
     * Answers "nothing is missing" when the check itself fails, which is the right way to fail
     * here: the translation then goes ahead and reports ML Kit's own error if a model really was
     * absent, where answering "everything is missing" would refuse one that would have worked.
     */
    private suspend fun missingModels(languages: List<String>): List<String> = try {
        val present = downloadedTags().toSet()
        languages.filterNot { it in present }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failed: Throwable) {
        Log.w(TAG, "Downloaded translation models could not be checked", failed)
        emptyList()
    }

    private suspend fun downloadedTags(): List<String> =
        models.getDownloadedModels(TranslateRemoteModel::class.java).await()
            .orEmpty()
            .mapNotNull { it.language }
}

/**
 * The open [Translator]s, one per ordered language pair, bounded.
 *
 * Its own class rather than three more members on [MlKitTranslation], because it owns a resource
 * with a lifetime — the eviction rule, the locking and the closing are one idea, and they read
 * better together than threaded through a translator.
 *
 * **Bounded where `MlKitAi`'s client map is not**, and the difference is forced rather than
 * stylistic: a [Translator] holds native resources and must be closed, where a `GenerativeModel`
 * does not and is not. There is also one of these per *pair* rather than per tier, so an unbounded
 * map is a slow leak on any macro that translates into whatever it was asked for.
 *
 * Caching at all is what makes the second translation fast: building a client is where the models
 * are loaded, so rebuilding per call would pay that on every run of a macro that fires hourly.
 */
private class TranslatorCache {

    private val clients = LinkedHashMap<String, Translator>()

    /**
     * The client for one ordered pair, building and caching it if need be.
     *
     * Synchronized because two macros may translate at once and a [Translator] built twice is a
     * leaked one; the map is also the eviction order, so it cannot be touched concurrently.
     */
    fun get(source: String, target: String): Translator = synchronized(clients) {
        val key = "$source$SEPARATOR$target"
        clients.remove(key)?.let { existing ->
            // Re-inserting is what makes the LinkedHashMap's order least-recently-used rather than
            // insertion order, which is the whole basis of the eviction below.
            clients[key] = existing
            return existing
        }
        val built = MlKitTranslator.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build(),
        )
        clients[key] = built
        while (clients.size > MAX_CLIENTS) {
            clients.remove(clients.keys.first())?.close()
        }
        built
    }

    /** Closes and forgets every cached client involving [language], in either direction. */
    fun closeFor(language: String) = synchronized(clients) {
        clients.keys
            .filter { language in it.split(SEPARATOR) }
            .forEach { clients.remove(it)?.close() }
    }

    private companion object {
        const val SEPARATOR = ">"

        /**
         * How many translators to keep open.
         *
         * Small on purpose. Each holds loaded models, and the realistic macro translates between
         * one or two pairs — a cache large enough to hold every pair a user might ever configure
         * would be holding models for pairs last used weeks ago.
         */
        const val MAX_CLIENTS = 4
    }
}

/**
 * Bridges a Play Services [Task] to a coroutine.
 *
 * Hand-rolled rather than taken from `kotlinx-coroutines-play-services`, on the argument the version
 * catalogue makes for the other hand-rolled pieces: this is four lines against a dependency, and it
 * is `LocationLookup`'s existing idiom in this codebase. It resumes with the failure rather than
 * swallowing it, because every caller above already has a `catch` that turns one into the sentence
 * the user reads.
 */
private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWith(Result.failure(it)) }
}

private fun unsupported(language: String): String = "The translator does not support '$language'"

/**
 * The sentence for a translation whose models are not on the phone.
 *
 * Names the languages *and* names the screen, because the way this is reached is by deleting a
 * model a macro was using: the macro worked yesterday, nothing about the graph changed, and
 * "translation failed" would send somebody looking in entirely the wrong place.
 */
private fun notDownloaded(languages: List<String>): String =
    "Not downloaded: ${languages.joinToString(", ")}. Add it under Setup, Translation models."
