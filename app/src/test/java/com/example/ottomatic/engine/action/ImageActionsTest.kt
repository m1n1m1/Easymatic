package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.ImageFacts
import com.example.ottomatic.core.service.ImageListing
import com.example.ottomatic.core.service.ImageOperation
import com.example.ottomatic.core.service.ImageRecord
import com.example.ottomatic.core.service.ImageWrite
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.MetadataDetail
import com.example.ottomatic.core.service.WhenExists
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingImages
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six `action.image_*` nodes over the outcomes that must not be collapsed.
 *
 * Three load-bearing assertions, each guarding a distinction a user acts on:
 *
 * - **A blank picture field reaches the facade not at all.** A node asked to delete
 *   nothing must report a mistake, not pass an empty handle down to be interpreted.
 * - **`needsConfirmation` survives to the port and stays out of `error` alone.** "Nobody
 *   could be asked" is worth retrying where "you said no" is not, and a macro can only
 *   branch on the difference if it reaches the struct.
 * - **`changed = false` with no error is not a failure**, so it logs at INFO. A tidy-up
 *   macro that finds nothing to tidy must not fill the console with errors.
 */
class ImageActionsTest {

    private val logs = mutableListOf<LogEntry>()
    private val images = RecordingImages()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        images = images,
        logger = { logs += it },
    )

    private fun levels() = logs.map { it.level }

    // -- a blank field never reaches the facade ----------------------------------------

    @Test
    fun `deleting with no picture named calls nothing and reports`() = runBlocking {
        val out = ImageDeleteAction().execute(ImageDeleteConfig(image = "   "), context)

        assertEquals(0, images.calls)
        assertFalse(out.value.changed)
        assertTrue(out.value.error.isNotBlank())
        assertEquals(listOf(LogLevel.ERROR), levels())
    }

    @Test
    fun `moving with no destination named calls nothing and says which field is missing`() = runBlocking {
        val out = ImageMoveAction().execute(
            ImageMoveConfig(image = "/DCIM/Camera/a.jpg", toFolder = ""),
            context,
        )

        assertEquals(0, images.calls)
        assertTrue(
            "The message must name the folder, not the picture",
            out.value.error.contains("folder"),
        )
    }

    @Test
    fun `editing with no picture named calls nothing`() = runBlocking {
        ImageEditAction().execute(ImageEditConfig(image = ""), context)

        assertEquals(0, images.calls)
    }

    // -- the three outcomes of a write -------------------------------------------------

    @Test
    fun `needing confirmation reaches the port and logs as an error`() = runBlocking {
        images.write = ImageWrite(
            changed = false,
            needsConfirmation = true,
            error = "Android needs you to confirm this",
        )

        val out = ImageDeleteAction().execute(ImageDeleteConfig(image = "/DCIM/a.jpg"), context)

        assertTrue("A macro must be able to branch on this", out.value.needsConfirmation)
        assertFalse(out.value.changed)
        assertEquals(
            "It is the only outcome the user can fix from Settings",
            listOf(LogLevel.ERROR),
            levels(),
        )
    }

    @Test
    fun `a refusal is not the same as nobody being asked`() = runBlocking {
        images.write = ImageWrite(changed = false, error = "You did not allow this change")

        val out = ImageDeleteAction().execute(ImageDeleteConfig(image = "/DCIM/a.jpg"), context)

        assertFalse("A decision is not a retryable state", out.value.needsConfirmation)
        assertEquals(listOf(LogLevel.WARN), levels())
    }

    @Test
    fun `nothing to do is not a failure`() = runBlocking {
        images.write = ImageWrite(changed = false, error = "")

        val out = ImageDeleteAction().execute(ImageDeleteConfig(image = "/DCIM/gone.jpg"), context)

        assertFalse(out.value.changed)
        assertTrue(out.value.error.isBlank())
        assertEquals(listOf(LogLevel.INFO), levels())
    }

    // -- the receipt carries what actually happened ------------------------------------

    /**
     * MediaStore renames silently on a collision, so the name a macro asked for is not
     * necessarily the one that now exists. `FileResultItem.name` learned this for SAF.
     */
    @Test
    fun `the receipt carries the name that now exists rather than the one asked for`() = runBlocking {
        images.write = ImageWrite(
            changed = true,
            image = ImageRecord(name = "holiday (1).jpg", path = "/Pictures/Ottomatic/holiday (1).jpg"),
        )

        val out = ImageEditAction().execute(
            ImageEditConfig(image = "/DCIM/a.jpg", name = "holiday.jpg"),
            context,
        )

        assertEquals("holiday (1).jpg", out.value.name)
    }

    // -- config reaches the facade in the shape the facade expects ---------------------

    @Test
    fun `the bin is the default and asks for the bin`() = runBlocking {
        ImageDeleteAction().execute(ImageDeleteConfig(image = "/DCIM/a.jpg"), context)

        assertEquals(listOf("/DCIM/a.jpg" to true), images.deletes)
    }

    /**
     * On a phone with no bin the node deletes for good — and says so *before* doing it,
     * because afterwards the picture is gone and the warning would arrive too late to
     * mean anything.
     */
    @Test
    fun `no recoverable bin degrades to a permanent delete with a warning`() = runBlocking {
        images.hasRecoverableBin = false

        ImageDeleteAction().execute(ImageDeleteConfig(image = "/DCIM/a.jpg"), context)

        assertTrue(
            "\"recoverable\" quietly becoming \"gone\" is not something to hide",
            logs.any { it.level == LogLevel.WARN && it.message.contains("for good") },
        )
    }

    @Test
    fun `a permanent delete asks for a permanent delete and warns about nothing`() = runBlocking {
        ImageDeleteAction().execute(
            ImageDeleteConfig(image = "/DCIM/a.jpg", removal = ImageRemoval.PERMANENT),
            context,
        )

        assertEquals(listOf("/DCIM/a.jpg" to false), images.deletes)
        assertFalse(logs.any { it.level == LogLevel.WARN })
    }

    @Test
    fun `a move is a move and a copy is a copy`() = runBlocking {
        ImageMoveAction().execute(
            ImageMoveConfig(image = "/DCIM/a.jpg", toFolder = "/Pictures/Keep"),
            context,
        )
        ImageMoveAction().execute(
            ImageMoveConfig(
                image = "/DCIM/b.jpg",
                toFolder = "/Pictures/Keep",
                operation = TransferOp.COPY,
                whenExists = WriteCollision.SKIP,
            ),
            context,
        )

        assertEquals(listOf(true, false), images.transfers.map { it.move })
        assertEquals(WhenExists.SKIP, images.transfers[1].whenExists)
    }

    @Test
    fun `a blank metadata value reaches the facade as blank, which is how a tag is removed`() = runBlocking {
        ImageMetadataAction().execute(
            ImageMetadataConfig(
                image = "/DCIM/a.jpg",
                detail = ImageDetailField.LOCATION,
                value = "  ",
            ),
            context,
        )

        assertEquals(
            listOf(Triple("/DCIM/a.jpg", MetadataDetail.LOCATION, "")),
            images.metadata,
        )
    }

    @Test
    fun `a rotate carries its degrees clockwise`() = runBlocking {
        ImageEditAction().execute(
            ImageEditConfig(image = "/DCIM/a.jpg", operation = ImageOp.ROTATE, turn = ImageTurn.LEFT),
            context,
        )

        val edit = images.edits.single().second
        assertEquals(ImageOperation.ROTATE, edit.operation)
        assertEquals("a left turn is 270 clockwise", 270, edit.turnDegrees)
    }

    // -- listing -----------------------------------------------------------------------

    @Test
    fun `a truncated listing is announced rather than passed off as the whole gallery`() = runBlocking {
        images.listing = ImageListing(
            images = listOf(ImageRecord(name = "a.jpg")),
            ok = true,
            truncated = true,
        )

        val out = ImageListAction().execute(ImageListConfig(limit = 1), context)

        assertEquals(1, out.value.size)
        assertEquals(listOf(LogLevel.WARN), levels())
    }

    @Test
    fun `a missing grant is a failure with an empty list, not an empty gallery`() = runBlocking {
        images.listing = ImageListing(error = "Ottomatic does not have access to your photos")

        val out = ImageListAction().execute(ImageListConfig(), context)

        assertTrue(out.value.isEmpty())
        assertEquals(listOf(LogLevel.WARN), levels())
    }

    // -- details -----------------------------------------------------------------------

    /**
     * "This photo was taken nowhere" and "I may not tell you where" must not read alike,
     * which is the whole reason `locationHidden` is a field of its own.
     */
    @Test
    fun `a hidden location is reported as hidden rather than as absent`() = runBlocking {
        images.facts = ImageFacts(
            exists = true,
            image = ImageRecord(name = "a.jpg"),
            hasLocation = false,
            locationHidden = true,
        )

        val out = ImageInfoAction().execute(ImageInfoConfig(image = "/DCIM/a.jpg"), context)

        assertFalse(out.value.hasLocation)
        assertTrue(out.value.locationHidden)
        assertTrue(logs.single().message.contains("hiding where it was taken"))
    }

    @Test
    fun `a picture that is not there is an answer rather than an error`() = runBlocking {
        images.facts = ImageFacts(exists = false)

        val out = ImageInfoAction().execute(ImageInfoConfig(image = "/DCIM/gone.jpg"), context)

        assertFalse(out.value.exists)
        assertTrue(out.value.error.isBlank())
        assertEquals(listOf(LogLevel.INFO), levels())
    }
}
