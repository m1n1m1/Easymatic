package com.example.ottomatic.data.translate

/**
 * The editor's half of the translator: which languages this phone has, adding one, removing one.
 *
 * **A second interface over the same implementation, holding what a macro may not reach** —
 * `OnDeviceSetup`'s arrangement, and now for that class's reason word for word rather than an
 * adapted version of it. `OnDeviceSetup.download`'s KDoc says *"the only caller is a button
 * somebody pressed"*, and here that is literally true: [download] is reached from the Translation
 * models screen and from nowhere else. `Translation` has no downloading member at all, so an
 * unattended macro cannot start one, and neither can a model calling `action.translate` as a tool.
 *
 * The size argument that would have excused doing otherwise — tens of megabytes rather than
 * gigabytes — is not needed and is not made. Whoever taps a language here has decided to spend the
 * data, on a screen that exists to be asked, which is a better answer than any default a node
 * could have carried.
 *
 * ### Why there is no progress and no Wi-Fi condition
 *
 * Both are absences forced by the library rather than chosen. ML Kit reports a model download as a
 * single `Task` that completes or fails — there is no `DownloadStatus` stream of the kind
 * `OnDeviceAi.download` has, so [download] can only suspend and the screen can only show a
 * spinner. And a `DownloadConditions` requiring Wi-Fi does not *fail* off Wi-Fi, it waits
 * indefinitely; with a person watching a spinner that is strictly worse than downloading over
 * whatever connection they have, having just asked for it.
 *
 * Nothing here throws: [download] and [delete] answer a sentence, the two lists answer empty.
 */
interface TranslationSetup {

    /**
     * Every language the translator could handle, as BCP-47 tags, sorted.
     *
     * A constant of the library, which is what the *add* chooser offers. Its counterpart
     * [installed] is the subset that is actually on the phone, and the node's own fields offer that
     * one — see `TranslateLanguages` for why the two are kept apart.
     */
    fun supported(): List<String>

    /**
     * The languages whose models are on this phone right now, as BCP-47 tags, sorted.
     *
     * Not cached, and it republishes `TranslateLanguages.hydrateDownloaded` as a side effect, which
     * is how the node's pickers learn what this screen did.
     */
    suspend fun installed(): List<String>

    /** Fetches one language's model, answering a problem or `""`. */
    suspend fun download(language: String): String

    /** Deletes one language's model, answering a problem or `""`. */
    suspend fun delete(language: String): String
}

/** The default where there is no translator: nothing to offer, nothing to add, nothing to remove. */
object NoTranslationSetup : TranslationSetup {
    override fun supported(): List<String> = emptyList()
    override suspend fun installed(): List<String> = emptyList()
    override suspend fun download(language: String): String = UNAVAILABLE
    override suspend fun delete(language: String): String = UNAVAILABLE

    private const val UNAVAILABLE = "Translation is not available on this phone"
}
