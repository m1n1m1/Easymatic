package com.example.ottomatic.feature.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two decisions inside an `@IntentChoice` launch that can be wrong without anything
 * saying so.
 *
 * Both are pure precisely so they can be tested here. Everything else in [IntentRequests]
 * needs an `Intent`, a `ContentResolver` or a `PackageManager`, which are `android.jar`
 * stubs that throw `Stub!` under plain JUnit — the same fact that made `PluginChannel` an
 * interface, applied to a smaller problem. What is left over needs a device, and the device
 * pass is what covers it.
 *
 * The failures these prevent are both invisible: an extra that never arrives leaves the app
 * being asked with no suggested filename or no destination, and picking the wrong answer
 * source stores nothing while that app reports success.
 */
class IntentRequestsTest {

    // ---- what goes on the launch -------------------------------------------

    @Test
    fun `an ordinary pair becomes one extra`() {
        assertEquals(
            listOf("android.intent.extra.TITLE" to "report.csv"),
            IntentRequests.extrasOf(listOf("android.intent.extra.TITLE=report.csv")),
        )
    }

    /**
     * Only the **first** `=` separates.
     *
     * A base64 pad and a query string both contain one legitimately, so a `split('=')`
     * would truncate the value and hand the app being asked something subtly different
     * from what the declaration said.
     */
    @Test
    fun `only the first equals separates, so a value may contain more`() {
        assertEquals(
            listOf("q" to "a=b=c"),
            IntentRequests.extrasOf(listOf("q=a=b=c")),
        )
    }

    /** An empty value is a real one — some flags are set by presence, not by content. */
    @Test
    fun `a pair with nothing after the equals is kept with a blank value`() {
        assertEquals(listOf("FLAG" to ""), IntentRequests.extrasOf(listOf("FLAG=")))
    }

    /**
     * A blank key is dropped rather than sent.
     *
     * An extra under `""` is one no app reads, and on the far side it is indistinguishable
     * from one that was never sent — so dropping it here is the same outcome, arrived at
     * where it can be seen.
     */
    @Test
    fun `an entry with a blank key is dropped`() {
        assertEquals(emptyList<Pair<String, String>>(), IntentRequests.extrasOf(listOf("=value")))
    }

    /**
     * An entry with no `=` at all is dropped here and **refused** at declaration time.
     *
     * Two guards rather than one because they run for different people at different moments:
     * `NodeSchema.checkIntentChoice` and `PluginDeclarationValidator` fail the author's build
     * and the plugin's first bind, while this is what happens if one ever gets past them.
     */
    @Test
    fun `an entry with no equals at all is dropped`() {
        assertEquals(
            emptyList<Pair<String, String>>(),
            IntentRequests.extrasOf(listOf("android.intent.extra.TITLE")),
        )
    }

    @Test
    fun `entries keep their declared order`() {
        assertEquals(
            listOf("a" to "1", "b" to "2", "c" to "3"),
            IntentRequests.extrasOf(listOf("a=1", "b=2", "c=3")),
        )
    }

    // ---- which answer the launch produced ------------------------------------

    @Test
    fun `the declared extra wins over everything else`() {
        assertEquals(
            "scanned",
            IntentRequests.answerOf(fromExtra = "scanned", fromData = "content://d", fromOutput = "content://o"),
        )
    }

    /** What every document and pick action answers with. */
    @Test
    fun `the result's own data is used when no extra was named`() {
        assertEquals(
            "content://d",
            IntentRequests.answerOf(fromExtra = null, fromData = "content://d", fromOutput = "content://o"),
        )
    }

    /**
     * The destination is the last resort, and the only thing that makes a camera work.
     *
     * `ACTION_IMAGE_CAPTURE` given an `EXTRA_OUTPUT` answers `RESULT_OK` with no data
     * whatsoever, because the photograph is already where it was told to put it. Without
     * this fallback a successful capture would look exactly like a cancelled one.
     */
    @Test
    fun `the supplied destination is used when the app answered with nothing`() {
        assertEquals(
            "content://o",
            IntentRequests.answerOf(fromExtra = null, fromData = null, fromOutput = "content://o"),
        )
    }

    /**
     * A blank is not an answer and falls through to the next source.
     *
     * An app answering `RESULT_OK` with an empty extra has told us nothing, and storing the
     * empty string would *clear* a field the user had already filled in — which is the one
     * outcome a chooser must never produce, on `finishWithoutChoosing`'s rule.
     */
    @Test
    fun `a blank answer falls through rather than clearing the field`() {
        assertEquals(
            "content://d",
            IntentRequests.answerOf(fromExtra = "", fromData = "content://d", fromOutput = null),
        )
        assertNull(IntentRequests.answerOf(fromExtra = "", fromData = "", fromOutput = null))
    }

    @Test
    fun `nothing anywhere is no answer at all`() {
        assertNull(IntentRequests.answerOf(fromExtra = null, fromData = null, fromOutput = null))
    }
}
