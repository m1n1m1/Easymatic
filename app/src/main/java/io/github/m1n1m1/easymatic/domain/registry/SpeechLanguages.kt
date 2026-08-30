package io.github.m1n1m1.easymatic.domain.registry

/**
 * Which languages this phone can speak and which it can understand, as a lookup anything
 * in `domain` may reach.
 *
 * A hydrated registry on [MqttCatalog]'s pattern, and the smallest of them: two lists of
 * BCP-47 tags published by `AndroidSpeech` once the platform has said what it has. It
 * exists because `Suggestions` runs in `domain`, where `TextToSpeech` is not reachable and
 * must never become so — the same reason [DeviceCapabilities] exists beside
 * `AndroidCapabilityChecker`.
 *
 * **Two lists rather than one, because the two halves genuinely disagree.** A phone that
 * speaks eleven languages may recognise three, and offering a Listen node a language it
 * can only *speak* would suggest a value that quietly never matches anything. Merging them
 * would be tidier and would make one of the two fields wrong.
 *
 * [isHydrated] carries [MqttCatalog]'s reasoning unchanged, including the part that
 * matters: an unhydrated registry **narrows nothing**. Both lists answer empty, the
 * `@Suggested` field behaves exactly as the plain text box it would otherwise be, and a
 * typed tag is accepted without comment. Suggestions were never a restriction here.
 */
object SpeechLanguages {

    @Volatile
    private var spoken: List<String>? = null

    @Volatile
    private var heard: List<String>? = null

    @Volatile
    private var installed: List<String>? = null

    /** Whether anything has published either list yet; see the class KDoc. */
    val isHydrated: Boolean get() = spoken != null || heard != null

    /** The languages a voice is installed for, or empty when nothing has been read. */
    fun forSpeaking(): List<String> = spoken.orEmpty()

    /**
     * The languages to offer for listening — what is **installed** where the phone will
     * say, and what is merely supported where it will not.
     *
     * **Three lists rather than two, because "supported" and "installed" are a real and
     * user-visible difference.** `ACTION_GET_LANGUAGE_DETAILS` answers what the recogniser
     * *can* do, which on a modern phone is well over a hundred languages; only a handful
     * have their model downloaded, and picking one of the others transcribes nothing and
     * fails with a message about a language pack. Offering the long list is offering
     * mostly wrong answers.
     *
     * Installed is only knowable from API 33, where `checkRecognitionSupport` reports it.
     * Below that — and on any phone whose recogniser declines to answer — this falls back
     * to the supported list, which is the honest degradation: a longer list of which some
     * entries need a download beats no list at all, which is what this field showed until
     * 2026-08-29.
     */
    fun forListening(): List<String> = installed?.takeIf { it.isNotEmpty() } ?: heard.orEmpty()

    /** Whether [forListening] is answering installed packs rather than merely supported ones. */
    fun listeningIsInstalledOnly(): Boolean = !installed.isNullOrEmpty()

    /** Publishes the languages the text-to-speech engine reported. */
    fun hydrateSpeaking(languages: List<String>) {
        spoken = languages
    }

    /**
     * Publishes the languages the recognition service reported.
     *
     * Separate from [hydrateSpeaking] because the two answers arrive at different moments
     * and by different routes — one from an init callback, one from a broadcast reply —
     * and a single setter would make whichever came second erase the other.
     */
    fun hydrateListening(languages: List<String>) {
        heard = languages
    }

    /**
     * Publishes the languages whose recognition model is actually **downloaded**.
     *
     * Separate from [hydrateListening] for that function's own reason — the two answers
     * arrive by different routes at different moments, so one setter would let whichever
     * came second erase the other — and because they mean different things, which
     * [forListening] is where it matters.
     */
    fun hydrateInstalledListening(languages: List<String>) {
        installed = languages
    }

    /** Returns to the unhydrated state. Test seam, mirroring [MqttCatalog.reset]. */
    internal fun reset() {
        spoken = null
        heard = null
        installed = null
    }
}
