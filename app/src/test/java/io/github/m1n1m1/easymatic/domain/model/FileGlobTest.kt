package io.github.m1n1m1.easymatic.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileGlobTest {

    @Test
    fun `a star matches any run of characters`() {
        assertTrue(FileGlob.matches("report.csv", "*.csv"))
        assertTrue(FileGlob.matches("q3-report.csv", "*.csv"))
        assertFalse(FileGlob.matches("report.txt", "*.csv"))
    }

    @Test
    fun `a star matches nothing at all`() {
        assertTrue(FileGlob.matches(".csv", "*.csv"))
        assertTrue(FileGlob.matches("report", "report*"))
    }

    @Test
    fun `a question mark matches exactly one character`() {
        assertTrue(FileGlob.matches("IMG_0042.jpg", "IMG_????.jpg"))
        assertFalse(FileGlob.matches("IMG_042.jpg", "IMG_????.jpg"))
        assertFalse(FileGlob.matches("IMG_00042.jpg", "IMG_????.jpg"))
    }

    @Test
    fun `several stars work together`() {
        assertTrue(FileGlob.matches("2026-08-report-final.csv", "*report*.csv"))
        assertFalse(FileGlob.matches("2026-08-summary.csv", "*report*.csv"))
    }

    // The volumes this runs against disagree about case, so the filter must not.
    @Test
    fun `matching ignores case`() {
        assertTrue(FileGlob.matches("PHOTO.JPG", "*.jpg"))
        assertTrue(FileGlob.matches("photo.jpg", "*.JPG"))
    }

    // Nothing configured narrows nothing — the degradation rule the scoped choosers
    // follow, applied here so an empty filter cannot look like an empty folder.
    @Test
    fun `a blank pattern matches everything`() {
        assertTrue(FileGlob.matches("anything at all", ""))
        assertTrue(FileGlob.matches("", "   "))
    }

    // A dot is literal, which is the whole reason this is a glob and not a regex:
    // read as a regex, `*.csv` is a valid pattern that matches nothing, and a filter
    // that silently matches nothing is indistinguishable from an empty folder.
    @Test
    fun `a dot is literal rather than any character`() {
        assertTrue(FileGlob.matches("a.csv", "?.csv"))
        assertFalse(FileGlob.matches("axcsv", "a.csv"))
    }

    @Test
    fun `a pattern with no wildcards is an exact match`() {
        assertTrue(FileGlob.matches("run.txt", "run.txt"))
        assertFalse(FileGlob.matches("run.txt.bak", "run.txt"))
        assertFalse(FileGlob.matches("run", "run.txt"))
    }

    @Test
    fun `surrounding whitespace in the pattern is ignored`() {
        assertTrue(FileGlob.matches("report.csv", "  *.csv  "))
    }
}
