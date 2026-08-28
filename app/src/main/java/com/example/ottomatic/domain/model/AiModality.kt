package com.example.ottomatic.domain.model

/**
 * A kind of input a model will accept.
 *
 * **The set a chooser filters on, and the reason it is here rather than in `data/ai/`:**
 * the thing that reads it is the model picker in `feature/`, and `feature` may reach
 * `domain` where it may not reach `data`. The parsing lives beside each provider's
 * listing; the vocabulary lives here, next to [AiProvider].
 *
 * Deliberately **input** modalities only. Output is text in every case this app can use —
 * a node's port is a Text port — so an image-generating model is not a thing the picker
 * could usefully offer, and a second axis nobody filters on is a second axis to keep
 * right.
 *
 * The set is the union of what the providers publish rather than what this app currently
 * sends. [VIDEO] and [FILE] reach no node today; listing them is what lets a chooser say
 * truthfully what a model does instead of quietly rounding it down to the three
 * Ottomatic happens to use.
 */
enum class AiModality {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
    FILE,
}
