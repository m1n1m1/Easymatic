package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the two things that make a macro's icon and accent safe to have added.
 *
 * Both failures they guard against are silent. Getting the first wrong deletes
 * every workflow on the device the next time the app starts; getting the second
 * wrong re-registers geofences and re-enqueues periodic work every time somebody
 * changes a colour. Neither would throw, and neither would be noticed in a debug
 * build with two test macros in it.
 */
class MacroAppearanceTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A workflow written before [Workflow.icon] and [Workflow.accent] existed still
     * loads, at the current schema version, with the defaults filled in.
     *
     * This is the one that matters. `WorkflowRepository.load` drops anything whose
     * `schemaVersion` is below [Workflow.CURRENT_SCHEMA_VERSION] — it does not
     * migrate it — so adding a field the *wrong* way (a required property, or a
     * version bump to go with it) does not fail loudly. It quietly discards
     * everything the user has ever made.
     */
    @Test
    fun `a workflow saved before appearance existed still loads`() {
        val legacy = """
            {
              "id": "abc",
              "name": "Morning Routine",
              "schemaVersion": ${Workflow.CURRENT_SCHEMA_VERSION},
              "nodes": [
                {"id": "n1", "typeId": "trigger.manual", "name": "Manual Trigger", "x": 0.0, "y": 0.0}
              ],
              "enabled": true
            }
        """.trimIndent()

        val loaded = json.decodeFromString(Workflow.serializer(), legacy)

        assertEquals("Morning Routine", loaded.name)
        assertEquals(true, loaded.enabled)
        assertEquals(1, loaded.nodes.size)
        assertEquals(MacroIcon.BOLT, loaded.icon)
        assertEquals(MacroAccent.SYSTEM, loaded.accent)
        // The version is what `load` gates on, so a file that decodes but reports
        // an older version is still thrown away.
        assertEquals(Workflow.CURRENT_SCHEMA_VERSION, loaded.schemaVersion)
    }

    /** And it round-trips, so a legacy file re-saved keeps everything it had. */
    @Test
    fun `appearance survives a round trip`() {
        val original = Workflow(
            id = "abc",
            name = "Torch",
            icon = MacroIcon.FLASHLIGHT,
            accent = MacroAccent.VIOLET,
        )
        val restored = json.decodeFromString(
            Workflow.serializer(),
            json.encodeToString(Workflow.serializer(), original),
        )
        assertEquals(MacroIcon.FLASHLIGHT, restored.icon)
        assertEquals(MacroAccent.VIOLET, restored.accent)
    }

    /**
     * Recolouring a macro must not re-arm it.
     *
     * The editor compares `runtimeSignature()` against the one it last armed to
     * decide whether a save has to re-arm the running macro, and re-arming
     * re-registers geofences and re-enqueues periodic work. Appearance is cosmetic,
     * so it has to stay out of that comparison.
     */
    @Test
    fun `runtimeSignature ignores icon and accent`() {
        val base = Workflow(
            id = "abc",
            name = "Torch",
            nodes = listOf(WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "M", 0f, 0f)),
        )
        val recoloured = base.copy(icon = MacroIcon.FLASHLIGHT, accent = MacroAccent.RED)

        assertEquals(base.runtimeSignature(), recoloured.runtimeSignature())
    }

    /** The counterpart, so the test above cannot pass by the signature being constant. */
    @Test
    fun `runtimeSignature still notices a config change`() {
        val base = Workflow(
            id = "abc",
            nodes = listOf(WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "M", 0f, 0f)),
        )
        val edited = base.copy(
            nodes = listOf(
                WorkflowNode(
                    NodeId("n1"),
                    NodeTypeId("trigger.manual"),
                    "M",
                    0f,
                    0f,
                    config = mapOf(io.github.m1n1m1.easymatic.core.model.ConfigKey("label") to "Start"),
                ),
            ),
        )
        assertNotEquals(base.runtimeSignature(), edited.runtimeSignature())
    }
}
