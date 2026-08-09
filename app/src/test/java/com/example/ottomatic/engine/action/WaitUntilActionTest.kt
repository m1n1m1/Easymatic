package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.ExecPorts
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.domain.registry.ConfigSchemaRegistry
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * What moment `action.wait_until` picks, and what it does when there is not one.
 *
 * The arithmetic is tested through [WaitUntilConfig.resumeAt] with an explicit
 * "now", so nothing here depends on the clock while it runs. [begin] is exercised
 * separately for the one thing only it decides: whether there is anything to wait
 * for at all.
 */
class WaitUntilActionTest {

    private val node = WaitUntilAction()
    private val context = DefaultExecutionContext(RecordingSystemServices())

    @Test
    fun `a duration is measured from now`() {
        val now = at(2026, Calendar.JULY, 27, hour = 9)
        val config = WaitUntilConfig(mode = WaitMode.DURATION, duration = 90, unit = DelayUnit.MINUTES)
        assertEquals(at(2026, Calendar.JULY, 27, hour = 10, minute = 30), config.resumeAt(now))
    }

    /**
     * Zero is a moment that has arrived, not one that never will — so it resumes
     * at once rather than taking the never-resumes branch.
     */
    @Test
    fun `a zero duration resumes immediately rather than never`() {
        val now = at(2026, Calendar.JULY, 27, hour = 9)
        val config = WaitUntilConfig(mode = WaitMode.DURATION, duration = 0)
        assertEquals(now, config.resumeAt(now))
    }

    @Test
    fun `a time of day finds the next one, honouring the day filters`() {
        // Monday the 27th at 09:00; Fridays only, so the next 07:30 is the 31st.
        val now = at(2026, Calendar.JULY, 27, hour = 9)
        val config = WaitUntilConfig(mode = WaitMode.TIME_OF_DAY, atTime = "07:30", friday = true)
        assertEquals(at(2026, Calendar.JULY, 31, hour = 7, minute = 30), config.resumeAt(now))
    }

    /** The clamp `TimeOfDay` applies, reached through this node's own field. */
    @Test
    fun `an unreadable time of day is read as midnight rather than throwing`() {
        val now = at(2026, Calendar.JULY, 27, hour = 9)
        val config = WaitUntilConfig(mode = WaitMode.TIME_OF_DAY, atTime = "nonsense")
        assertEquals(at(2026, Calendar.JULY, 28), config.resumeAt(now))
    }

    @Test
    fun `a date in the future is waited for, and one in the past is not`() {
        val now = at(2026, Calendar.JULY, 27, hour = 9)
        val ahead = at(2026, Calendar.JULY, 27, hour = 22)
        assertEquals(ahead, WaitUntilConfig(mode = WaitMode.DATE_TIME, until = DateTime(ahead)).resumeAt(now))
        val behind = at(2026, Calendar.JULY, 27, hour = 8)
        assertNull(WaitUntilConfig(mode = WaitMode.DATE_TIME, until = DateTime(behind)).resumeAt(now))
    }

    /**
     * The default is [DateTime.EPOCH], so an untouched node in this mode has
     * nothing to wait for. That makes the never-resumes branch the ordinary case
     * rather than an edge one, which is why it has to report itself.
     */
    @Test
    fun `an unconfigured date has nothing to wait for`() = runBlocking {
        val fork = node.beginRaw(placed(mode = "DATE_TIME"), emptyMap(), context)
        assertNull(fork.resume)
        assertTrue(fork.values.isEmpty())
    }

    @Test
    fun `a configured wait announces the moment it plans to resume at`() = runBlocking {
        val fork = node.beginRaw(placed(mode = "DURATION"), emptyMap(), context)
        assertNotNull(fork.resume)
        val planned = fork.values.getValue(WAIT_PLANNED_OUT).value as DateTime
        assertTrue("$planned is not ahead", planned.epochMs > System.currentTimeMillis())
    }

    /** A date arriving over a wire is parsed by the same lenient reader a typed one is. */
    @Test
    fun `a wired date is read as a date`() = runBlocking {
        val ahead = System.currentTimeMillis() + 3_600_000L
        val fork = node.beginRaw(
            placed(mode = "DATE_TIME"),
            mapOf(PortName("until") to Item.of(DateTime(ahead))),
            context,
        )
        assertNotNull("a wired date was ignored", fork.resume)
    }

    @Test
    fun `it declares both fork outputs and a date port for each`() {
        val definition = NodeTypeRegistry.byId(NodeTypeId("action.wait_until"))!!
        val execOut = definition.outputs(PortKind.EXECUTION).map { it.name }
        assertEquals(listOf(ExecPorts.OUT, ExecPorts.RESUMED), execOut)

        val dates = definition.outputs(PortKind.DATA)
        assertEquals(listOf(WAIT_PLANNED_OUT, WAIT_RESUMED_OUT), dates.map { it.name })
        assertTrue(dates.all { it.schema == ItemSchema.Primitive(DateTime::class) })
    }

    /** The clock face, not a text field — the same rendering the schedule trigger gets. */
    @Test
    fun `the time of day field gets a clock and the date field a calendar`() {
        val fields = ConfigSchemaRegistry.byId(NodeTypeId("action.wait_until"))!!.fields
        assertEquals(ConfigFieldType.TIME_OF_DAY, fields.single { it.key == ConfigKey("atTime") }.type)
        assertEquals(ConfigFieldType.DATE_TIME, fields.single { it.key == ConfigKey("until") }.type)
    }

    private fun placed(mode: String) = WorkflowNode(
        NodeId("w"), NodeTypeId("action.wait_until"), "Wait Until", 0f, 0f,
        config = mapOf(ConfigKey("mode") to mode),
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
