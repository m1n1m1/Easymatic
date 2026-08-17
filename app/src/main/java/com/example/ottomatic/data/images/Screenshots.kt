package com.example.ottomatic.data.images

import android.content.Context
import android.provider.MediaStore

/**
 * The one reading of "is this picture a screenshot?", and of where a new one goes.
 *
 * It exists because **the answer is a different folder on different phones** —
 * `Pictures/Screenshots` on AOSP and most OEMs, `DCIM/Screenshots` on others — and that is
 * precisely the kind of platform detail a person configuring a macro must never have to
 * know. `trigger.image_saved` already offers a folder field and its own KDoc argues against
 * a "Camera / Screenshots / Anywhere" enum on exactly this ground: an enum would be
 * inventing vocabulary the platform does not have. A *node* whose whole subject is
 * screenshots is the other answer to the same objection — the user says what they mean and
 * the app resolves which folder that is here, in one place, shared by the trigger that
 * watches, the value that reads and the action that writes.
 *
 * Pure enough to test on the JVM apart from [writeFolder], which needs a cursor.
 */
internal object Screenshots {

    /** Where a capture goes when this phone has no screenshots to learn from. */
    const val FALLBACK_FOLDER: String = "Pictures/Screenshots"

    /**
     * Whether [folder] — a `RELATIVE_PATH`-shaped folder like `Pictures/Screenshots` — is
     * somewhere this phone keeps screenshots.
     *
     * **Any segment, not just the last**, so `Pictures/Screenshots/Chrome` counts: a
     * browser or a messenger filing its captures in a sub-folder has still taken a
     * screenshot. `startsWith` rather than equality covers the OEMs spelling it in the
     * singular.
     *
     * **The folder and never the file name.** A picture called `Screenshot_2026-08-17.png`
     * sitting in `Download` arrived from somebody else's phone through a messenger, and
     * reporting it as a screenshot this phone took would fire a macro for something that
     * never happened. The name is a copyable string; the folder is where the system put it.
     */
    fun isScreenshotFolder(folder: String): Boolean =
        folder.split('/').any { segment ->
            segment.trim().lowercase().startsWith(SEGMENT)
        }

    /**
     * The folder `action.screenshot` writes into: whichever one this phone already uses.
     *
     * Asked of the collection rather than assumed, so a capture lands in the *same gallery
     * album* as the ones taken with the hardware buttons instead of creating a second
     * "Screenshots" album beside it. Cached for the life of the process — a phone does not
     * move its screenshot folder — and falling back to [FALLBACK_FOLDER] when there is
     * nothing to learn from.
     *
     * **A missing `READ_MEDIA_IMAGES` lands on the fallback**, not on an error. That is why
     * `action.screenshot` does not declare the grant: it writes a row it owns, needs no
     * read access to do so, and would otherwise be badged in the Problems panel for a
     * permission it only ever used to make a good guess prettier.
     */
    fun writeFolder(context: Context): String {
        cached?.let { return it }
        val found = newestScreenshotFolder(context) ?: FALLBACK_FOLDER
        cached = found
        return found
    }

    /**
     * The SQL half of [isScreenshotFolder], for narrowing a query before Kotlin decides.
     *
     * The same split `MediaImages.query` uses for the file glob and `ImageWatchers` uses
     * for its pattern: `LIKE` cannot express the rule above — it matches a *name* segment
     * as happily as a folder one — so it is used only to keep the cursor small, and
     * [isScreenshotFolder] is what actually answers. `LIKE` is case-insensitive for ASCII
     * in SQLite, so no lowercasing is needed on this side.
     */
    fun selection(): Pair<String, Array<String>> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("%$SEGMENT%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%$SEGMENT%")
        }

    /**
     * Newest first, sorted on `DATE_ADDED` rather than `DATE_TAKEN` — which is null for a
     * screenshot, so sorting on it would put every candidate at one end.
     */
    fun sortOrder(): String =
        "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"

    private fun newestScreenshotFolder(context: Context): String? {
        val (selection, args) = selection()
        val rows = MediaStoreQueries.map(
            context = context,
            selection = selection,
            args = args,
            sortOrder = sortOrder(),
            limit = SCAN_LIMIT,
        ) { MediaStoreQueries.recordOf(context, it) } ?: return null
        return rows.firstOrNull { isScreenshotFolder(it.folder) }
            ?.folder
            ?.trim('/')
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * How far down the newest-first list to look before giving up.
     *
     * The `LIKE` above already narrowed to rows mentioning "screenshot" somewhere, so the
     * first row is nearly always the answer; the rest of the budget is for a phone where
     * that word turns up in a file name outside any screenshot folder.
     */
    private const val SCAN_LIMIT = 50

    private const val SEGMENT = "screenshot"

    @Volatile
    private var cached: String? = null
}
