package com.example.ottomatic.feature.ai

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import com.example.ottomatic.R
import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.domain.model.AiModality
import com.example.ottomatic.domain.model.AiProvider

/**
 * Everything the AI screens say *about* a provider, in one place.
 *
 * **Spelled out here rather than read off the enum's `@Label`**, which is
 * `MailAccountEditorOverlay`'s deliberate choice for its own enums: that annotation
 * is reachable only through a serialization descriptor, which a screen has no other
 * reason to hold, and the label a node's config form needs is not always the sentence
 * a settings screen wants. The cost is one exhaustive `when` per thing said — and
 * that is the point, because a new provider then fails to compile here until somebody
 * has decided what the screens call it and where its key comes from.
 *
 * These return **resource ids rather than strings**, the direct route: the answer set
 * is a closed enum, so the compiler checks the `when` in both directions and a missing
 * string fails the build. It also keeps the file free of Android context, so it stays
 * callable from anywhere that can reach a `Resources`.
 *
 * The console URL matters more than it looks. An API key is minted in a page most
 * people have never opened, and printing a URL to be transcribed into a browser is
 * the same failure a `@Picker` exists to prevent, one layer out — so every provider
 * that has a console gets a button that opens it. [consoleUrl] and
 * [SELF_HOSTED_PRESETS] stay plain strings: an address and a product name are not
 * translatable.
 */
@StringRes
internal fun AiProvider.labelRes(): Int = when (this) {
    AiProvider.GEMINI -> R.string.ai_provider_gemini
    AiProvider.ANTHROPIC -> R.string.ai_provider_anthropic
    AiProvider.OPENAI -> R.string.ai_provider_openai
    AiProvider.OPENROUTER -> R.string.ai_provider_openrouter
    AiProvider.OPENAI_COMPATIBLE -> R.string.ai_provider_self_hosted
}

/** What a new connection is called before the user renames it. */
@StringRes
internal fun AiProvider.defaultNameRes(): Int = when (this) {
    AiProvider.GEMINI -> R.string.ai_default_name_gemini
    AiProvider.ANTHROPIC -> R.string.ai_default_name_anthropic
    AiProvider.OPENAI -> R.string.ai_default_name_openai
    AiProvider.OPENROUTER -> R.string.ai_default_name_openrouter
    AiProvider.OPENAI_COMPATIBLE -> R.string.ai_default_name_self_hosted
}

/** Where this provider's keys are minted, or blank when there is no page to open. */
internal fun AiProvider.consoleUrl(): String = when (this) {
    AiProvider.GEMINI -> "https://aistudio.google.com/apikey"
    AiProvider.ANTHROPIC -> "https://console.anthropic.com/settings/keys"
    AiProvider.OPENAI -> "https://platform.openai.com/api-keys"
    AiProvider.OPENROUTER -> "https://openrouter.ai/keys"
    // A server the user runs; whatever key it wants, they set it themselves.
    AiProvider.OPENAI_COMPATIBLE -> ""
}

/**
 * What that page is called, so the button can name where it goes.
 *
 * Null for the self-hosted provider, which has no console — the caller draws no
 * button at all there, so an empty string would be a value nothing renders.
 */
@StringRes
internal fun AiProvider.consoleNameRes(): Int? = when (this) {
    AiProvider.GEMINI -> R.string.ai_console_gemini
    AiProvider.ANTHROPIC -> R.string.ai_console_anthropic
    AiProvider.OPENAI -> R.string.ai_console_openai
    AiProvider.OPENROUTER -> R.string.ai_console_openrouter
    AiProvider.OPENAI_COMPATIBLE -> null
}

/**
 * How to get a key, in the order it is actually done.
 *
 * Steps rather than a paragraph because it is a procedure in *another app*: somebody
 * following it is switching back and forth and needs to find their place again,
 * which prose does not let them do. A `string-array` rather than one key per step,
 * so a translator can add or drop a step where a provider's flow differs.
 */
@ArrayRes
internal fun AiProvider.setupStepsRes(): Int = when (this) {
    AiProvider.GEMINI -> R.array.ai_steps_gemini
    AiProvider.ANTHROPIC -> R.array.ai_steps_anthropic
    AiProvider.OPENAI -> R.array.ai_steps_openai
    AiProvider.OPENROUTER -> R.array.ai_steps_openrouter
    AiProvider.OPENAI_COMPATIBLE -> R.array.ai_steps_self_hosted
}

/**
 * The closing note under the key field.
 *
 * Per provider because the honest sentence differs: two of these have a free tier
 * worth naming, one is a credit balance, and one sends nothing anywhere at all.
 */
@StringRes
internal fun AiProvider.costNoteRes(): Int = when (this) {
    AiProvider.GEMINI -> R.string.ai_cost_gemini
    AiProvider.ANTHROPIC -> R.string.ai_cost_anthropic
    AiProvider.OPENAI -> R.string.ai_cost_openai
    AiProvider.OPENROUTER -> R.string.ai_cost_openrouter
    AiProvider.OPENAI_COMPATIBLE -> R.string.ai_cost_self_hosted
}

/**
 * Where prompts actually go, which differs enough between providers to be worth
 * saying — and for the last one is the whole selling point rather than a footnote.
 */
@StringRes
internal fun AiProvider.privacyNoteRes(): Int = when (this) {
    AiProvider.GEMINI -> R.string.ai_privacy_gemini
    AiProvider.ANTHROPIC -> R.string.ai_privacy_anthropic
    AiProvider.OPENAI -> R.string.ai_privacy_openai
    AiProvider.OPENROUTER -> R.string.ai_privacy_openrouter
    AiProvider.OPENAI_COMPATIBLE -> R.string.ai_privacy_self_hosted
}

/**
 * The user's own word for each of `AiModel`'s three tiers.
 *
 * Here rather than read off the enum for the reason at the top of this file — and
 * because `AiModel` lives in `core`, which may not import the `@Label` annotation at
 * all, so there is nothing on it to read.
 */
@StringRes
internal fun AiModel.labelRes(): Int = when (this) {
    AiModel.FAST -> R.string.ai_model_fast
    AiModel.BALANCED -> R.string.ai_model_balanced
    AiModel.THOROUGH -> R.string.ai_model_thorough
}

/**
 * Ready-made addresses for the servers people actually run.
 *
 * `MailProvider`'s preset table, for its reason: these are not a `@Picker`'s
 * open-ended option set, they are the four ports somebody would otherwise look up.
 * The host is left as a placeholder because only the user knows it — which is the
 * whole reason this field cannot be a chooser. Product names and addresses, so
 * nothing here is translated.
 */
internal val SELF_HOSTED_PRESETS: List<Pair<String, String>> = listOf(
    "Ollama" to "http://192.168.1.10:11434/v1",
    "LM Studio" to "http://192.168.1.10:1234/v1",
    "vLLM" to "http://192.168.1.10:8000/v1",
    "llama.cpp" to "http://192.168.1.10:8080/v1",
)

/**
 * What an input kind is called on screen.
 *
 * Beside the provider copy rather than on [AiModality] itself, for the reason every
 * label in this file sits here: `@StringRes` lives in `feature/`, and the enum is in
 * `domain/` where `R` cannot be reached. An exhaustive `when` so a sixth modality fails
 * to compile until somebody has decided what the screens call it.
 */
@StringRes
internal fun AiModality.labelRes(): Int = when (this) {
    AiModality.TEXT -> R.string.ai_modality_text
    AiModality.IMAGE -> R.string.ai_modality_image
    AiModality.AUDIO -> R.string.ai_modality_audio
    AiModality.VIDEO -> R.string.ai_modality_video
    AiModality.FILE -> R.string.ai_modality_file
}
