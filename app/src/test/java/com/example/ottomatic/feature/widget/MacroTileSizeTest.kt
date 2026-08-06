package com.example.ottomatic.feature.widget

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a tile's contents fit the space the tile has.
 *
 * This is the failure the deck already had once — a cell height picked by eye that
 * was 10dp shorter than the chip, gap and label it contained, so every label in
 * every deck lost its lower half. Nothing throws when that happens and no test that
 * checks behaviour notices; the only thing that catches it is arithmetic, which is
 * why both heights are written as sums and compared here rather than typed as
 * numbers that happen to work.
 */
class MacroTileSizeTest {

    /**
     * The narrow arrangement's whole premise is that a label fits at 1×1, so it has
     * to fit at the smallest size a launcher can hand the widget — not at the larger
     * one most of them actually do.
     */
    @Test
    fun `the narrow tile fits the smallest run tile bucket`() {
        assertTrue(
            "Narrow tile content is $NARROW_CONTENT_HEIGHT, taller than the " +
                "${SMALLEST_SIZE.height} bucket it has to fit — its label will clip.",
            NARROW_CONTENT_HEIGHT <= SMALLEST_SIZE.height,
        )
    }

    /** The chip shrank so the label could exist; if it grows back, the label goes. */
    @Test
    fun `the narrow chip is smaller than a deck chip`() {
        assertTrue(CHIP_SIZE_NARROW < CHIP_SIZE)
    }

    /** A deck cell is measured from its contents, so it can never be short of them. */
    @Test
    fun `a deck cell is as tall as what it holds`() {
        assertTrue(CELL_HEIGHT >= CHIP_SIZE + CHIP_LABEL_GAP)
    }
}
