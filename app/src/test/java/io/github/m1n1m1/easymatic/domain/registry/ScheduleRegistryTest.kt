package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the consolidated time triggers: `trigger.schedule` (which absorbed
 * the old sleep, stopwatch and time-tick nodes) and `trigger.clock_changed`
 * (which absorbed the date-change and timezone-change nodes).
 *
 * The merged nodes replace five registered type ids, so this also asserts the
 * old ones are gone — leaving one behind would leave a node in the palette whose
 * implementation no longer exists.
 */
class ScheduleRegistryTest {

    private val scheduleType = NodeTypeId("trigger.schedule")
    private val clockChangeType = NodeTypeId("trigger.clock_changed")

    @Test
    fun `the merged triggers are registered and the nodes they replaced are gone`() {
        assertNotNull(TriggerRegistry.byId(scheduleType))
        assertNotNull(TriggerRegistry.byId(clockChangeType))
        for (retired in RETIRED_TYPE_IDS) {
            assertNull("$retired was merged away and must not be registered", TriggerRegistry.byId(retired))
            assertNull("$retired must not appear in the palette", NodeTypeRegistry.byId(retired))
        }
    }

    @Test
    fun `schedule is a trigger exposing one exec out and one typed fireTime out`() {
        val def = NodeTypeRegistry.byId(scheduleType)
        assertNotNull(def)
        assertEquals(NodeKind.TRIGGER, def!!.kind)
        val exec = def.ports.filter { it.kind == PortKind.EXECUTION }
        val dataOut = def.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
        assertEquals(1, exec.size)
        assertEquals(PortName("out"), exec.single().name)
        assertEquals(1, dataOut.size)
        assertEquals(PortName("fireTime"), dataOut.single().name)
        assertTrue(def.ports.none { it.kind == PortKind.DATA && it.direction == Direction.IN })
    }

    @Test
    fun `the fireTime schema carries the elapsed and calendar fields the old nodes had to compute`() {
        val port = NodeTypeRegistry.byId(scheduleType)!!.ports.first { it.name == PortName("fireTime") }
        val schema = port.schema as ItemSchema.Object
        assertEquals(
            setOf("firedAt", "elapsedMs", "count", "hour", "minute", "dayOfWeek", "dayOfMonth"),
            schema.fields.keys,
        )
    }

    @Test
    fun `the declared config covers both modes with the expected defaults`() {
        val schema = ConfigSchemaRegistry.byId(scheduleType)
        assertNotNull(schema)
        val byKey = schema!!.fields.associateBy { it.key }
        assertEquals("INTERVAL", byKey.getValue(ConfigKey("mode")).defaultValue)
        assertEquals("15", byKey.getValue(ConfigKey("every")).defaultValue)
        assertEquals("MINUTES", byKey.getValue(ConfigKey("everyUnit")).defaultValue)
        assertEquals("07:30", byKey.getValue(ConfigKey("atTime")).defaultValue)
        assertNotNull(byKey[ConfigKey("monday")])
        assertNotNull(byKey[ConfigKey("daysOfMonth")])
        assertEquals("false", byKey.getValue(ConfigKey("windowEnabled")).defaultValue)
        assertEquals("22:00", byKey.getValue(ConfigKey("windowFrom")).defaultValue)
        assertEquals("07:00", byKey.getValue(ConfigKey("windowUntil")).defaultValue)
    }

    @Test
    fun `the form shows the interval settings in interval mode and the time in at-time mode`() {
        val definition = NodeTypeRegistry.byId(scheduleType)!!

        val interval = visibleKeys(definition, placed(scheduleType, "mode" to "INTERVAL"))
        assertTrue(interval.containsAll(setOf("every", "everyUnit", "windowEnabled")))
        assertTrue("atTime belongs to the other mode", "atTime" !in interval)

        val atTime = visibleKeys(definition, placed(scheduleType, "mode" to "AT_TIME"))
        assertTrue("atTime" in atTime)
        assertTrue("interval settings belong to the other mode", atTime.none { it == "every" || it == "windowEnabled" })

        // The day filters apply to both modes, so they are never hidden.
        assertTrue(interval.containsAll(DAY_KEYS))
        assertTrue(atTime.containsAll(DAY_KEYS))
    }

    @Test
    fun `the window times appear only once the window toggle is on`() {
        val definition = NodeTypeRegistry.byId(scheduleType)!!

        val off = visibleKeys(definition, placed(scheduleType, "mode" to "INTERVAL"))
        assertTrue("windowFrom" !in off && "windowUntil" !in off)

        val on = visibleKeys(
            definition,
            placed(scheduleType, "mode" to "INTERVAL", "windowEnabled" to "true"),
        )
        assertTrue(on.containsAll(setOf("windowFrom", "windowUntil")))
    }

    @Test
    fun `a hidden toggle also hides the fields it controls`() {
        // Switching to at-time mode hides `windowEnabled`; its own fields have to
        // go with it, or the user is left with two time boxes and no visible
        // switch explaining them.
        val definition = NodeTypeRegistry.byId(scheduleType)!!
        val visible = visibleKeys(
            definition,
            placed(scheduleType, "mode" to "AT_TIME", "windowEnabled" to "true"),
        )
        assertTrue(visible.none { it in setOf("windowEnabled", "windowFrom", "windowUntil") })
    }

    @Test
    fun `an unconfigured node shows the fields of its default mode`() {
        val definition = NodeTypeRegistry.byId(scheduleType)!!
        val visible = visibleKeys(definition, placed(scheduleType))
        assertTrue("the default mode is INTERVAL", "every" in visible)
        assertTrue("atTime" !in visible)
    }

    @Test
    fun `clock changed offers an optional filter over both clock events`() {
        val def = NodeTypeRegistry.byId(clockChangeType)
        assertNotNull(def)
        assertEquals(NodeKind.TRIGGER, def!!.kind)
        assertEquals(
            listOf(PortName("state")),
            def.ports.filter { it.kind == PortKind.DATA }.map { it.name },
        )
        val field = ConfigSchemaRegistry.byId(clockChangeType)!!.fields.single()
        val options = (field.type as ConfigFieldType.ENUM).options
        assertEquals(listOf("", "DATE_CHANGED", "TIMEZONE_CHANGED"), options.map { it.value })
        assertEquals("unset means fire on either event", "Any", options.first().label)
    }

    private fun visibleKeys(
        definition: io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition,
        node: WorkflowNode,
    ): Set<String> {
        val workflow = Workflow(id = "w1", name = "w", nodes = listOf(node))
        return effectiveConfigSchema(definition, workflow, node)!!.fields.map { it.key.value }.toSet()
    }

    private fun placed(typeId: NodeTypeId, vararg config: Pair<String, String>) = WorkflowNode(
        id = NodeId("n1"),
        typeId = typeId,
        name = typeId.value,
        x = 0f,
        y = 0f,
        config = config.associate { (key, value) -> ConfigKey(key) to value },
    )

    private companion object {
        val RETIRED_TYPE_IDS = listOf(
            NodeTypeId("trigger.sleep"),
            NodeTypeId("trigger.stopwatch"),
            NodeTypeId("trigger.time_tick"),
            NodeTypeId("trigger.date_change"),
            NodeTypeId("trigger.timezone_change"),
        )
        val DAY_KEYS = setOf("monday", "sunday", "daysOfMonth")
    }
}
