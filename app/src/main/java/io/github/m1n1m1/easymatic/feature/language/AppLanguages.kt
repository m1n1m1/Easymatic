package io.github.m1n1m1.easymatic.feature.language

import java.util.Locale

/**
 * The languages the app can be shown in: one tag per `values-*` folder plus `values/`.
 *
 * A constant rather than the `locales_config.xml` AGP generates from the same folders,
 * because that file is an internal (its resource name starts with an underscore), a
 * debug build lists the pseudolocales in it too, and reading it needs a `Context`.
 * `AppLanguagesTest` walks the resource folders instead, so the two cannot drift.
 */
internal object AppLanguages {

    /** BCP-47 tags, in the order the changelog lists the languages. */
    val tags: List<String> = listOf("en-GB", "de", "es", "fr", "ja", "pt", "ru", "zh-CN")

    /**
     * Which of [tags] an applied locale list means, or null for "System default".
     *
     * An exact match first; failing that, the first tag in the same language, so a
     * `de-AT` applied from the phone's own per-app page still highlights German.
     */
    fun selectedTag(appliedLanguageTags: String): String? {
        val applied = appliedLanguageTags.substringBefore(',').trim()
        if (applied.isBlank()) return null
        val language = Locale.forLanguageTag(applied).language
        return tags.firstOrNull { it == applied }
            ?: tags.firstOrNull { Locale.forLanguageTag(it).language == language }
    }

    /**
     * A tag as its own language's name for itself ("Deutsch", "日本語"), so the user
     * can find their language whatever the app is currently shown in.
     */
    fun nameOf(tag: String): String {
        val locale = runCatching { Locale.forLanguageTag(tag) }.getOrNull() ?: return tag
        val name = locale.getDisplayName(locale)
        return if (name.isBlank()) tag else name.replaceFirstChar { it.titlecase(locale) }
    }
}
