package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.service.ScreenRotation
import io.github.m1n1m1.easymatic.domain.registry.ActionRegistry
import io.github.m1n1m1.easymatic.domain.registry.ConfigFieldType
import io.github.m1n1m1.easymatic.domain.registry.ConfigSchemaRegistry
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import io.github.m1n1m1.easymatic.engine.trigger.gesture.DeviceOrientation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.screen_rotation` — turning the screen by hand.
 *
 * What is worth pinning here is the **size of the choice**: the vocabulary is
 * `trigger.device_orientation`'s minus the two resting positions no display can be
 * put into, so the two nodes read as one idea without the action offering a face-down
 * screen nothing could ever deliver.
 */
class ScreenRotationActionTest {

    private val services = RecordingSystemServices()
    private val context = DefaultExecutionContext(systemServices = services)
    private val action = ScreenRotationAction()

    @Test
    fun `the chosen rotation reaches the platform and comes back on the state port`() = runBlocking {
        val out = action.execute(ScreenRotationConfig(rotation = ScreenRotation.LANDSCAPE_LEFT), context)

        assertEquals(ScreenRotation.LANDSCAPE_LEFT, services.screenRotation)
        assertEquals(ScreenRotation.LANDSCAPE_LEFT, out.value.rotation)
        assertTrue(out.value.changed)
    }

    /**
     * Without `WRITE_SETTINGS` the node reports "did nothing" rather than failing the
     * run — the house shape for a settings write, and what makes the missing grant a
     * Problems-panel matter rather than a broken macro.
     */
    @Test
    fun `a refused change reports the rotation it wanted and changed false`() = runBlocking {
        services.screenRotationRefused = true

        val out = action.execute(ScreenRotationConfig(rotation = ScreenRotation.PORTRAIT_UPSIDE_DOWN), context)

        assertEquals(ScreenRotation.PORTRAIT_UPSIDE_DOWN, out.value.rotation)
        assertFalse(out.value.changed)
    }

    @Test
    fun `it is registered and offers the four positions a display can hold`() {
        assertNotNull(ActionRegistry.byId(NodeTypeId("action.screen_rotation")))

        val schema = ConfigSchemaRegistry.byId(NodeTypeId("action.screen_rotation"))!!
        val field = schema.fields.single { it.key == ConfigKey("rotation") }
        val options = (field.type as ConfigFieldType.ENUM).options

        // Not nullable: unset would mean "any rotation", which is a filter's idea and
        // not an instruction. Something has to be picked, and portrait is the default.
        assertEquals(ScreenRotation.entries.size, options.size)
        assertEquals("portrait", field.defaultValue)
        assertTrue(options.none { it.value.isBlank() })
    }

    /**
     * The trigger's six positions minus face up and face down, sharing every label with
     * it: a macro that says "when it goes face down, turn the screen to landscape left"
     * uses one word for one position on both cards.
     */
    @Test
    fun `the vocabulary is the orientation trigger's without the two that have no rotation`() {
        val fromTrigger = DeviceOrientation.entries
            .map { it.name }
            .filterNot { it == "FACE_UP" || it == "FACE_DOWN" }

        assertEquals(fromTrigger, ScreenRotation.entries.map { it.name })
    }
}
