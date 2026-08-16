package com.example.ottomatic.data.images

import com.example.ottomatic.core.service.ImageLimits

/**
 * Deciding how much of a picture to decode, before any of it is decoded.
 *
 * Pure and split out from `ImageEditor` on `FilePath`'s and `ImageDiff`'s reasoning: this
 * is the arithmetic that keeps `MacroEngineService` alive, and it is testable with two
 * integers where the pipeline around it needs `android.graphics`.
 *
 * **The bound is applied while decoding, never by shrinking afterwards.** A 50-megapixel
 * photo decoded whole is two hundred megabytes as `ARGB_8888`, and this runs beside every
 * armed macro — so "decode it and then resize" is not a slow edit but an out-of-memory
 * kill that takes the lot down. `FileLimits.MAX_READ_BYTES` states the same rule for bytes.
 */
internal object ImageScalePlan {

    /**
     * What to ask `BitmapFactory` for, and what to do to the result.
     *
     * [inSampleSize] is what the decoder is told; [exactWidth] and [exactHeight] are what
     * the matrix afterwards has to reach, because sampling only ever lands on a power of
     * two and almost never on the size the user asked for.
     */
    data class Plan(
        val inSampleSize: Int,
        val exactWidth: Int,
        val exactHeight: Int,
        /** True when [ImageLimits.MAX_DECODE_PIXELS] — not the request — decided the size. */
        val cappedByLimit: Boolean,
    )

    /**
     * Plans a decode of a [sourceWidth] x [sourceHeight] picture down to [maxSide].
     *
     * [maxSide] of zero or less means "no resize asked for", in which case the only thing
     * that can shrink the picture is the memory cap.
     *
     * **`inSampleSize` is a power of two, always.** `BitmapFactory` rounds anything else
     * *down* to one, so a plan computing 3 and expecting a third would silently get a
     * half — and every dimension downstream would be wrong by a factor the caller never
     * sees. Computing the power here is what makes [exactWidth] trustworthy.
     */
    fun plan(sourceWidth: Int, sourceHeight: Int, maxSide: Int): Plan {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return Plan(inSampleSize = 1, exactWidth = 0, exactHeight = 0, cappedByLimit = false)
        }

        val requested = if (maxSide > 0) maxSide else maxOf(sourceWidth, sourceHeight)
        // Never upscale. Asking for a 4000px side from a 1000px photo is a request for a
        // blurry 4000px photo, which is not what anybody means by "longest side 4000".
        val wanted = requested.coerceAtMost(maxOf(sourceWidth, sourceHeight))

        val afterRequest = fit(sourceWidth, sourceHeight, wanted)
        val capped = capToPixels(afterRequest.first, afterRequest.second)
        val cappedByLimit = capped != afterRequest

        val sample = sampleFor(sourceWidth, sourceHeight, capped.first, capped.second)
        return Plan(
            inSampleSize = sample,
            exactWidth = capped.first,
            exactHeight = capped.second,
            cappedByLimit = cappedByLimit,
        )
    }

    /** [width] x [height] scaled so its longest side is [longestSide], aspect kept. */
    private fun fit(width: Int, height: Int, longestSide: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= longestSide || longest == 0) return width to height
        val ratio = longestSide.toDouble() / longest
        return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
    }

    /** The same shape, shrunk until it fits [ImageLimits.MAX_DECODE_PIXELS]. */
    private fun capToPixels(width: Int, height: Int): Pair<Int, Int> {
        val pixels = width.toLong() * height.toLong()
        if (pixels <= ImageLimits.MAX_DECODE_PIXELS) return width to height
        val ratio = kotlin.math.sqrt(ImageLimits.MAX_DECODE_PIXELS.toDouble() / pixels)
        return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
    }

    /**
     * The largest power of two that does not take the decode *below* the target.
     *
     * Below, not to: sampling past the target throws away detail the matrix then has to
     * invent, so the decode lands at or above what is wanted and the exact scale happens
     * afterwards. That is the standard two-step, and the reason [Plan] carries both.
     */
    private fun sampleFor(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        if (targetWidth <= 0 || targetHeight <= 0) return 1
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= targetWidth &&
            sourceHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        return sample
    }
}
