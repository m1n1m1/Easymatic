package io.github.m1n1m1.easymatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePathTest {

    @Test
    fun `an absolute path keeps its segments and says it is absolute`() {
        val path = FilePath.parse("/storage/emulated/0/Documents/report.csv")
        assertTrue(path!!.isAbsolute)
        assertEquals(listOf("storage", "emulated", "0", "Documents", "report.csv"), path.segments)
        assertEquals("report.csv", path.name)
        assertEquals(listOf("storage", "emulated", "0", "Documents"), path.parent)
    }

    @Test
    fun `a bare name is relative and names the app's own storage`() {
        val path = FilePath.parse("scratch.txt")
        assertFalse(path!!.isAbsolute)
        assertEquals(listOf("scratch.txt"), path.segments)
        assertEquals(emptyList<String>(), path.parent)
    }

    @Test
    fun `a relative path may have folders in it`() {
        val path = FilePath.parse("logs/today/run.txt")
        assertFalse(path!!.isAbsolute)
        assertEquals(listOf("logs", "today", "run.txt"), path.segments)
    }

    // The security case, and the reason this class exists: the property behind it is
    // @Wired, so an HTTP response or a script result can carry the text.
    @Test
    fun `a dot dot segment is refused wherever it appears`() {
        assertNull(FilePath.parse(".."))
        assertNull(FilePath.parse("../secrets"))
        assertNull(FilePath.parse("logs/../../workflows/abc.json"))
        assertNull(FilePath.parse("/storage/emulated/0/../../data"))
    }

    // Refused before normalisation rather than after: `a/../b` resolves to `b`, which
    // is not what was written, and repairing it is exactly the guess not to make.
    @Test
    fun `a path that would normalise to something legal is still refused`() {
        assertNull(FilePath.parse("logs/../run.txt"))
    }

    @Test
    fun `a dot segment and a repeated separator are refused`() {
        assertNull(FilePath.parse("./run.txt"))
        assertNull(FilePath.parse("logs/./run.txt"))
        assertNull(FilePath.parse("logs//run.txt"))
        assertNull(FilePath.parse("/logs//run.txt"))
    }

    // A wired Windows path is a realistic input; refusing it here is diagnosable,
    // where accepting it would silently create a file with backslashes in its name.
    @Test
    fun `a backslash anywhere is refused`() {
        assertNull(FilePath.parse("C:\\Users\\report.csv"))
        assertNull(FilePath.parse("logs\\run.txt"))
    }

    @Test
    fun `control characters and NUL are refused`() {
        assertNull(FilePath.parse("run\u0000.txt"))
        assertNull(FilePath.parse("run\n.txt"))
        assertNull(FilePath.parse("run\t.txt"))
    }

    @Test
    fun `blank and separator-only text is refused`() {
        assertNull(FilePath.parse(""))
        assertNull(FilePath.parse("   "))
        assertNull(FilePath.parse("/"))
        assertNull(FilePath.parse("//"))
    }

    // Normalised rather than refused: the system folder chooser produces the trailing
    // form, and the two spellings cannot mean different folders.
    @Test
    fun `a trailing separator is normalised away`() {
        assertEquals("/storage/emulated/0/Documents", FilePath.parse("/storage/emulated/0/Documents/").toString())
        assertEquals("logs", FilePath.parse("logs/").toString())
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("/tmp/a.txt", FilePath.parse("  /tmp/a.txt  ").toString())
    }

    @Test
    fun `toString round-trips what parse accepted`() {
        val text = "/storage/emulated/0/Download/Easymatic/report.csv"
        assertEquals(text, FilePath.parse(text).toString())
    }

    @Test
    fun `equal paths are equal and hash alike`() {
        val one = FilePath.parse("/tmp/a.txt")!!
        val two = FilePath.parse("/tmp/a.txt/")!!
        assertEquals(one, two)
        assertEquals(one.hashCode(), two.hashCode())
    }

    // The one distinction the whole design rests on, so it is pinned rather than
    // left to the two tests above to imply.
    @Test
    fun `the same name is a different path absolute and relative`() {
        assertTrue(FilePath.parse("/notes.txt") != FilePath.parse("notes.txt"))
    }
}
