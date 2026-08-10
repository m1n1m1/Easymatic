package com.example.ottomatic.feature.ai

import com.example.ottomatic.core.service.AiModel
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
 * The console URL matters more than it looks. An API key is minted in a page most
 * people have never opened, and printing a URL to be transcribed into a browser is
 * the same failure a `@Picker` exists to prevent, one layer out — so every provider
 * that has a console gets a button that opens it.
 */
internal fun AiProvider.label(): String = when (this) {
    AiProvider.GEMINI -> "Google Gemini"
    AiProvider.ANTHROPIC -> "Anthropic Claude"
    AiProvider.OPENAI -> "OpenAI (ChatGPT)"
    AiProvider.OPENROUTER -> "OpenRouter"
    AiProvider.OPENAI_COMPATIBLE -> "Self-hosted / OpenAI-compatible"
}

/** What a new connection is called before the user renames it. */
internal fun AiProvider.defaultName(): String = when (this) {
    AiProvider.GEMINI -> "Gemini"
    AiProvider.ANTHROPIC -> "Claude"
    AiProvider.OPENAI -> "ChatGPT"
    AiProvider.OPENROUTER -> "OpenRouter"
    AiProvider.OPENAI_COMPATIBLE -> "Self-hosted"
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

/** What that page is called, so the button can name where it goes. */
internal fun AiProvider.consoleName(): String = when (this) {
    AiProvider.GEMINI -> "Google AI Studio"
    AiProvider.ANTHROPIC -> "the Anthropic Console"
    AiProvider.OPENAI -> "the OpenAI platform"
    AiProvider.OPENROUTER -> "OpenRouter"
    AiProvider.OPENAI_COMPATIBLE -> ""
}

/**
 * How to get a key, in the order it is actually done.
 *
 * Steps rather than a paragraph because it is a procedure in *another app*: somebody
 * following it is switching back and forth and needs to find their place again,
 * which prose does not let them do.
 */
@Suppress("MaxLineLength")
internal fun AiProvider.setupSteps(): List<String> = when (this) {
    AiProvider.GEMINI -> listOf(
        "Tap the button below. Google AI Studio opens in your browser.",
        "Sign in with your Google account if you are asked to.",
        "Tap \"Create API key\". If it asks which project to use, pick any — or let it make a new one for you.",
        "Tap the key to copy it.",
        "Come back here and tap the paste button beside the key field.",
    )
    AiProvider.ANTHROPIC -> listOf(
        "Tap the button below. The Anthropic Console opens in your browser.",
        "Sign in, or create an account if you do not have one.",
        "Tap \"Create Key\", give it a name, and confirm.",
        "Copy the key — it is shown once and never again.",
        "Come back here and tap the paste button beside the key field.",
    )
    AiProvider.OPENAI -> listOf(
        "Tap the button below. The OpenAI platform opens in your browser.",
        "Sign in, or create an account if you do not have one.",
        "Tap \"Create new secret key\" and confirm.",
        "Copy the key — it is shown once and never again.",
        "Come back here and tap the paste button beside the key field.",
    )
    AiProvider.OPENROUTER -> listOf(
        "Tap the button below. OpenRouter opens in your browser.",
        "Sign in, or create an account if you do not have one.",
        "Tap \"Create Key\" and confirm.",
        "Copy the key, then come back here and tap the paste button.",
        "Load the model list below and choose which model each speed setting uses.",
    )
    AiProvider.OPENAI_COMPATIBLE -> listOf(
        "Start your model server — vLLM, Ollama, LM Studio and llama.cpp all work.",
        "Put its address below, including http:// or https:// and the port.",
        "Paste its API key if it wants one. Many local servers accept anything.",
        "Load the model list, or type the model name your server was started with.",
    )
}

/**
 * The closing note under the key field.
 *
 * Per provider because the honest sentence differs: two of these have a free tier
 * worth naming, one is a credit balance, and one sends nothing anywhere at all.
 */
internal fun AiProvider.costNote(): String = when (this) {
    AiProvider.GEMINI ->
        "The free tier is generous, but it is your quota — a macro that asks the AI every " +
            "minute will use it up. You can revoke the key from the same page at any time."
    AiProvider.ANTHROPIC ->
        "Prompts are billed to your own Anthropic credit, so a macro that asks the AI every " +
            "minute costs money. You can revoke the key from the same page at any time."
    AiProvider.OPENAI ->
        "Prompts are billed to your own OpenAI account, so a macro that asks the AI every " +
            "minute costs money. You can revoke the key from the same page at any time."
    AiProvider.OPENROUTER ->
        "Prompts are billed to your own OpenRouter credit, and the price depends on which " +
            "model you choose. You can revoke the key from the same page at any time."
    AiProvider.OPENAI_COMPATIBLE ->
        "Prompts go to your own server and nowhere else, so they cost nothing and leave no " +
            "network you control. The server has to be reachable whenever the macro runs."
}

/**
 * Ready-made addresses for the servers people actually run.
 *
 * `MailProvider`'s preset table, for its reason: these are not a `@Picker`'s
 * open-ended option set, they are the four ports somebody would otherwise look up.
 * The host is left as a placeholder because only the user knows it — which is the
 * whole reason this field cannot be a chooser.
 */
/**
 * Where prompts actually go, which differs enough between providers to be worth
 * saying — and for the last one is the whole selling point rather than a footnote.
 */
internal fun AiProvider.privacyNote(): String = when (this) {
    AiProvider.GEMINI -> "Prompts are answered by Google's servers, so an Ask AI node takes a moment."
    AiProvider.ANTHROPIC -> "Prompts are answered by Anthropic's servers, so an Ask AI node takes a moment."
    AiProvider.OPENAI -> "Prompts are answered by OpenAI's servers, so an Ask AI node takes a moment."
    AiProvider.OPENROUTER ->
        "Prompts go through OpenRouter to whichever provider serves the model you chose, " +
            "so an Ask AI node takes a moment."
    AiProvider.OPENAI_COMPATIBLE -> "Prompts go only to the server you named above."
}

/**
 * The user's own word for each of `AiModel`'s three tiers.
 *
 * Here rather than read off the enum for the reason at the top of this file — and
 * because `AiModel` lives in `core`, which may not import the `@Label` annotation at
 * all, so there is nothing on it to read.
 */
internal fun AiModel.label(): String = when (this) {
    AiModel.FAST -> "Fast"
    AiModel.BALANCED -> "Balanced"
    AiModel.THOROUGH -> "Thorough"
}

internal val SELF_HOSTED_PRESETS: List<Pair<String, String>> = listOf(
    "Ollama" to "http://192.168.1.10:11434/v1",
    "LM Studio" to "http://192.168.1.10:1234/v1",
    "vLLM" to "http://192.168.1.10:8000/v1",
    "llama.cpp" to "http://192.168.1.10:8080/v1",
)
