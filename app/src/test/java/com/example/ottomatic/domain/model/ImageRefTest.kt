package com.example.ottomatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImageRef] over the shapes a picture field actually holds.
 *
 * The load-bearing assertions are the two that are security rather than parsing: a
 * `content://` handle must be recognised **before** [FilePath] gets a chance to read it as
 * a relative path, and a `file://` URI must be **refused** rather than accepted — it names
 * a real file and would reach the filesystem without having been through the `..` gate,
 * which is the one thing that gate exists to prevent.
 */
class ImageRefTest {

    @Test
    fun `a content uri is a row handle`() {
        val ref = ImageRef.parse("content://media/external/images/media/42")

        assertTrue(ref is ImageRef.Uri)
        assertEquals("content://media/external/images/media/42", ref.toString())
    }

    /**
     * The ordering bug this exists to prevent: a content uri is full of `/`, so `FilePath`
     * would happily read it as a relative path named `content:` and resolve it inside the
     * app's own storage, where it would quietly find nothing.
     */
    @Test
    fun `a content uri is not read as a path`() {
        assertTrue(ImageRef.parse("content://media/external/images/media/42") is ImageRef.Uri)
    }

    @Test
    fun `an absolute path is a path handle`() {
        val ref = ImageRef.parse("/storage/emulated/0/DCIM/Camera/IMG_1.jpg")

        assertTrue(ref is ImageRef.Path)
        assertEquals("/storage/emulated/0/DCIM/Camera/IMG_1.jpg", ref.toString())
    }

    @Test
    fun `a relative path is a path handle`() {
        assertTrue(ImageRef.parse("scratch/photo.jpg") is ImageRef.Path)
    }

    @Test
    fun `a file uri is refused rather than treated as a path`() {
        assertNull(
            "A file:// uri would reach the filesystem without passing FilePath's gate",
            ImageRef.parse("file:///storage/emulated/0/DCIM/x.jpg"),
        )
    }

    @Test
    fun `an http url is refused`() {
        assertNull(ImageRef.parse("https://example.com/cat.jpg"))
    }

    @Test
    fun `a parent traversal is refused`() {
        assertNull(ImageRef.parse("../../etc/passwd"))
        assertNull(ImageRef.parse("a/../../b"))
    }

    @Test
    fun `a windows path is refused`() {
        assertNull(ImageRef.parse("C:\\Pictures\\x.jpg"))
    }

    @Test
    fun `blank is refused`() {
        assertNull(ImageRef.parse(""))
        assertNull(ImageRef.parse("   "))
    }

    @Test
    fun `a bare content scheme with no row is refused`() {
        assertNull(ImageRef.parse("content://"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("/DCIM/x.jpg", ImageRef.parse("  /DCIM/x.jpg  ").toString())
    }

    /** A colon inside a segment is far likelier to be a filename than a scheme. */
    @Test
    fun `a name containing a colon is still a path`() {
        assertTrue(ImageRef.parse("/DCIM/2026-08-16 10:15:00.jpg") is ImageRef.Path)
    }
}
