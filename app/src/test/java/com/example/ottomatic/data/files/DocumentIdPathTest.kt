package com.example.ottomatic.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading a `raw:` document id, which needs no volume table and so needs no device.
 *
 * It is here because of the `Download` folder. Android refuses a tree grant on that one
 * directory, so the only way to reach something already in it is to be handed that file
 * on its own — and a file picked out of the Downloads shortcut comes from the downloads
 * provider rather than from storage, which spells its ids differently. Without this
 * branch every such pick resolved to nothing, the field silently did not change, and the
 * button read as dead.
 *
 * The `volume:relative` half needs a `Context` for the volume table and is covered on a
 * device instead.
 */
class DocumentIdPathTest {

    @Test
    fun `a raw id is the path it carries`() {
        assertEquals(
            "/storage/emulated/0/Download/report.csv",
            rawDocumentPath("raw:/storage/emulated/0/Download/report.csv"),
        )
    }

    @Test
    fun `a raw id on a memory card is read the same way`() {
        assertEquals("/storage/1A2B-3C4D/Download/a.pdf", rawDocumentPath("raw:/storage/1A2B-3C4D/Download/a.pdf"))
    }

    /**
     * The other shape the downloads provider uses, and it is genuinely unresolvable:
     * `1000000123` is a row id, not a location. It has to fall through rather than be
     * guessed at, so the chooser can say the file cannot be named instead of storing a
     * grant nothing could ever address.
     */
    @Test
    fun `an opaque downloads id is not a raw path`() {
        assertNull(rawDocumentPath("msf:1000000123"))
    }

    @Test
    fun `a storage id is left to the volume table`() {
        assertNull(rawDocumentPath("primary:Documents/report.csv"))
    }

    /** A `raw:` that does not carry an absolute path is not one either. */
    @Test
    fun `a raw id without an absolute path is refused`() {
        assertNull(rawDocumentPath("raw:Download/report.csv"))
        assertNull(rawDocumentPath("raw:"))
    }

    @Test
    fun `an id with no colon at all is not a raw path`() {
        assertNull(rawDocumentPath("1000000123"))
    }
}
