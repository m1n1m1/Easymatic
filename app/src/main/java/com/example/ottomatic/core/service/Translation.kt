package com.example.ottomatic.core.service

/**
 * Turning text in one language into text in another — `action.translate`.
 *
 * Reached from `ExecutionContext` and nothing else, on [Speech]'s and [Files]' reasoning.
 * **Nothing here throws**: [translate] answers a result carrying an error string, so a node
 * reports what happened and takes its fallback rather than unwinding a run.
 *
 * ### One member, and why it is the only one
 *
 * There is **no `download`, no `installed`, no `delete`** — the whole model-management half lives
 * on `TranslationSetup`, which the Translation models screen holds and the executor cannot reach.
 * This follows `OnDeviceAi`'s rule rather than arguing its way around it: the engine-facing class
 * must never grow a method that fetches or removes an asset, because anything the executor can
 * reach a macro can be made to do — and since `action.translate` is offered to the tool harness
 * like any other action, anything a macro can do a *model* can decide to do.
 *
 * **Nothing here fetches anything, not even as a side effect.** A translation whose models are not
 * on the phone is refused with a sentence rather than made to wait, which is what lets this member
 * have no timeout, no network conditions and no progress. That is only tolerable because the
 * node's language fields offer *downloaded* languages only, so the ordinary way to reach this
 * refusal is to delete a model a macro was already using — and then the sentence naming the
 * language is exactly the right answer.
 *
 * There is no `identify` either: its one caller is [translate] with a blank
 * [TranslationRequest.sourceLanguage], and a member answering a bare tag could not say whether a
 * blank meant "the text was too short", "the identifier failed" or "there was nothing to
 * identify". What was detected comes back on [TranslationOutcome.sourceLanguage] instead, where it
 * sits beside the error that would explain it.
 *
 * ### Why the pull side may not touch this
 *
 * A translation is a neural inference over a loaded model. It is fast, but it is not the "cheap,
 * repeatable and cannot fail" a value node contracts for — it can fail, on a deleted model, and
 * failing closed is the whole reason this answers an outcome rather than a string.
 */
interface Translation {

    /**
     * Translates [TranslationRequest.text], answering what happened.
     *
     * Suspends for the translation itself, which is local and quick. Cancelling the caller
     * abandons it.
     *
     * A blank [TranslationRequest.sourceLanguage] means *work it out*, and the language settled on
     * comes back in [TranslationOutcome.sourceLanguage] whether it was detected or given.
     *
     * **A language whose model is absent is an error, never a download.** The message names the
     * language, because "translation failed" on a macro that worked yesterday is not something
     * anybody can act on and "the German model is no longer on this phone" is.
     *
     * **Text already in the target language is not a failure.** It comes back unchanged, with a
     * blank error, because a macro translating whatever arrives will meet that case constantly and
     * there is nothing for the user to fix.
     */
    suspend fun translate(request: TranslationRequest): TranslationOutcome
}

/**
 * What one translation should be.
 *
 * A request object rather than three parameters, on [SpeechRequest]'s reasoning.
 */
data class TranslationRequest(
    val text: String,
    /**
     * A BCP-47 tag naming the language [text] is in. **Blank means work it out**, which is what the
     * node's own `Detect automatically` resolves to.
     *
     * This is the one place a blank carries a meaning rather than being an omission, and the node
     * above it deliberately does *not* copy that: `TranslateConfig` uses an explicit two-value
     * enum, so a user who cleared the language box to retype it is not silently switched into
     * detection. The facade can afford the terser form because its only caller has already decided.
     */
    val sourceLanguage: String = "",
    /** A BCP-47 tag naming the language to produce. Blank is an error rather than a default. */
    val targetLanguage: String = "",
)

/**
 * What one translation produced.
 *
 * [sourceLanguage] is the language actually translated *from*, detected or given, and is blank only
 * when nothing was translated. It is reported rather than echoed back from the request for
 * [ListenOutcome.language]'s reason: a node that filled it in from its own config would be
 * inventing an answer. On a detected translation it is the only place that answer exists at all,
 * which is why the node logs it.
 *
 * [text] is empty whenever [error] is filled in — there is no partial third case.
 */
data class TranslationOutcome(
    val text: String = "",
    val sourceLanguage: String = "",
    val error: String = "",
)

/** The engine's default: no translator, failing closed with a sentence that says so. */
object NoTranslation : Translation {
    override suspend fun translate(request: TranslationRequest) =
        TranslationOutcome(error = "Translation is not available on this phone")
}
