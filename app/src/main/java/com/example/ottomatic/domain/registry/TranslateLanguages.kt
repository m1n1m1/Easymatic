package com.example.ottomatic.domain.registry

/**
 * The translator's languages, in the two versions that differ — what it *could* handle, and what
 * this phone has actually downloaded.
 *
 * A hydrated registry, published by `MlKitTranslation`, for the one reason [MacroDirectory] and
 * [CalendarDirectory] are: `domain` cannot import ML Kit and must never become able to.
 *
 * ### Two lists, because the two halves genuinely disagree
 *
 * [SpeechLanguages]' shape, and it earns it for that class's stated reason rather than by
 * imitation: a phone that can *speak* eleven languages may only *recognise* three, and offering
 * one list where two are true makes one of the fields wrong. Here the same split falls between
 * what the library supports — about sixty languages, a compile-time constant, identical on every
 * phone — and the handful whose models have been downloaded, which is a fact about this phone that
 * changes whenever somebody adds or removes one.
 *
 * **Which list a caller wants is decided by what a wrong answer would cost**, and the two callers
 * want opposite things:
 *
 * - The node's language fields, and `PickerOptions` behind them, want [downloaded]. Translation is
 *   the one thing this app does that simply cannot happen without an asset being present, so
 *   offering a language whose model is absent is offering a macro that is already broken —
 *   configured, valid-looking, and failing on its first run.
 * - The Translation models screen's *add* chooser wants [supported], because its whole job is to
 *   turn one of those into the other.
 *
 * ### Hydration is not once here, and [supported] versus [downloaded] is why
 *
 * [hydrateSupported] runs once at construction and could not sensibly run again. [hydrateDownloaded]
 * runs whenever the answer changes — after a download, after a delete, and after any read of the
 * installed list — because that answer is storage on a phone rather than a constant in a library.
 * Two setters rather than one for [SpeechLanguages]' reason exactly: a single one would let
 * whichever arrived second erase the other.
 *
 * Empty means "cannot be enumerated", never "there is nothing" — [PickerOptions]' degradation rule.
 * That matters most for [downloaded] in the moment before the first read returns, where a chooser
 * showing nothing is honest about not knowing yet rather than claiming nothing is installed.
 */
object TranslateLanguages {

    @Volatile
    private var supported: List<String>? = null

    @Volatile
    private var downloaded: List<String>? = null

    /** Whether anything has published either list yet; see the class KDoc. */
    val isHydrated: Boolean get() = supported != null || downloaded != null

    /** Every language the translator could handle, or empty when nothing has published. */
    fun supported(): List<String> = supported.orEmpty()

    /** The languages whose models are on this phone, or empty when nothing has published. */
    fun downloaded(): List<String> = downloaded.orEmpty()

    /** Publishes the library's fixed list. Called once, at construction. */
    fun hydrateSupported(languages: List<String>) {
        supported = languages
    }

    /**
     * Publishes what is downloaded right now.
     *
     * Called again on every change, unlike [hydrateSupported]; see the class KDoc. Separate from it
     * so that neither can erase the other.
     */
    fun hydrateDownloaded(languages: List<String>) {
        downloaded = languages
    }

    /** Returns to the unhydrated state. Test seam, mirroring [SpeechLanguages.reset]. */
    internal fun reset() {
        supported = null
        downloaded = null
    }
}
