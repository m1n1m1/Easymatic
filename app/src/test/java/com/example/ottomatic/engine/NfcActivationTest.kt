package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NfcTag
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.NfcScan
import com.example.ottomatic.engine.trigger.NfcStatus
import com.example.ottomatic.engine.trigger.NfcTagConfig
import com.example.ottomatic.engine.trigger.NfcTagTrigger
import com.example.ottomatic.engine.trigger.ScheduleHandle
import com.example.ottomatic.engine.trigger.TriggerHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `trigger.nfc` lets through, what it names, and what it says when it is armed
 * on a phone that can never deliver it a tap.
 *
 * [NfcFakeHost] serves a finite bus, so collecting the trigger's flow completes once
 * the scripted taps have been delivered rather than waiting forever.
 */
class NfcActivationTest {

    private val node = WorkflowNode(NodeId("n1"), NodeTypeId("trigger.nfc"), "NFC", 0f, 0f)

    private val desk = tap("04A23F1B")
    private val car = tap("0BADC0DE")

    @Test
    fun `an unconfigured trigger runs on every tag`() = runBlocking {
        val emitted = collect(NfcTagConfig(), desk, car)

        assertEquals(listOf("04A23F1B", "0BADC0DE"), emitted.map { it.tagId })
    }

    @Test
    fun `a configured tag filters out every other one`() = runBlocking {
        val emitted = collect(NfcTagConfig(tagId = "04A23F1B"), desk, car)

        assertEquals(listOf("04A23F1B"), emitted.map { it.tagId })
    }

    /**
     * The name comes from the library at fire time, not from the config — which is
     * what lets one "any tag" macro tell the desk from the car in a single
     * `action.if` instead of needing a macro per sticker.
     */
    @Test
    fun `the name is resolved from the library as the tap arrives`() = runBlocking {
        val host = NfcFakeHost(desk, car, tags = mapOf("04A23F1B" to "Desk"))

        val emitted = NfcTagTrigger().activate(NfcTagConfig(), node, host).toList().map { it.value }

        assertEquals(listOf("Desk", ""), emitted.map { it.tagName })
    }

    /** A tag's NDEF content rides along, so a tag can carry data as well as identity. */
    @Test
    fun `ndef text is carried through`() = runBlocking {
        val emitted = collect(NfcTagConfig(), tap("04A23F1B", text = "meeting"))

        assertEquals(listOf("meeting"), emitted.map { it.text })
    }

    /**
     * Nearly every tap is a cold start — the tag is what launched the process — so
     * this is the normal path rather than an edge case, and worth a line in the
     * console because "it ran a moment after I tapped" otherwise looks like a fault.
     */
    @Test
    fun `a tap that arrived while the engine was starting says so`() = runBlocking {
        val held = tap("04A23F1B").let { it.copy(payload = it.payload + (TriggerBus.KEY_HELD to "true")) }
        val host = NfcFakeHost(held)

        val emitted = NfcTagTrigger().activate(NfcTagConfig(), node, host).toList()

        assertEquals(1, emitted.size)
        assertTrue(host.reported.any { it.message.contains("while the engine was starting") })
    }

    /** An armed macro should say what it is armed for, not only show it on the card. */
    @Test
    fun `activation reports what is being watched`() = runBlocking {
        val anyTag = NfcFakeHost()
        NfcTagTrigger().activate(NfcTagConfig(), node, anyTag).toList()
        assertTrue(anyTag.reported.single().message.contains("any NFC tag"))

        val named = NfcFakeHost(tags = mapOf("04A23F1B" to "Desk"))
        NfcTagTrigger().activate(NfcTagConfig(tagId = "04A23F1B"), node, named).toList()
        assertTrue(named.reported.single().message.contains("'Desk'"))
    }

    /** A tag with no name falls back to the id, formatted so it can be read. */
    @Test
    fun `an unnamed tag is reported by its id`() = runBlocking {
        val host = NfcFakeHost()

        NfcTagTrigger().activate(NfcTagConfig(tagId = "04A23F1B"), node, host).toList()

        assertTrue(host.reported.single().message.contains("04:A2:3F:1B"))
    }

    /**
     * The two failures nothing else in the app reports. The Permissions screen calls
     * a phone with no chip *satisfied* on purpose — there is nothing there to grant —
     * and nothing at all knows about the per-app tag-intent switch. Both look
     * identical to a macro that is simply waiting.
     */
    @Test
    fun `a phone that can never deliver a tap says so`() = runBlocking {
        val noChip = NfcFakeHost(status = NfcStatus.NO_HARDWARE)
        NfcTagTrigger().activate(NfcTagConfig(), node, noChip).toList()
        val chipWarning = noChip.reported.single { it.level == LogLevel.WARN }
        assertTrue(chipWarning.message, chipWarning.message.contains("no NFC"))

        val blocked = NfcFakeHost(status = NfcStatus.TAG_INTENTS_BLOCKED)
        NfcTagTrigger().activate(NfcTagConfig(), node, blocked).toList()
        val blockedWarning = blocked.reported.single { it.level == LogLevel.WARN }
        assertTrue(blockedWarning.message, blockedWarning.message.contains("switched off for Ottomatic"))
    }

    /** A host that knows nothing about the radio must not make every trigger complain. */
    @Test
    fun `an unknown radio state is silent`() = runBlocking {
        val host = NfcFakeHost()

        NfcTagTrigger().activate(NfcTagConfig(), node, host).toList()

        assertTrue(host.reported.none { it.level == LogLevel.WARN })
    }

    private suspend fun collect(config: NfcTagConfig, vararg events: TriggerEvent): List<NfcScan> =
        NfcTagTrigger().activate(config, node, NfcFakeHost(*events)).toList().map { it.value }

    private fun tap(tagId: String, text: String = "") = TriggerEvent(
        source = TriggerSource.NFC,
        triggerNodeId = NodeId.BROADCAST,
        payload = mapOf(
            "tagId" to tagId,
            "text" to text,
            "timestamp" to "1000",
        ),
    )
}

/**
 * One line the trigger wrote to its workflow's console.
 *
 * Named apart from the other activation tests' identical helpers because
 * file-private top-level declarations still share a package namespace in Kotlin, so
 * two files in `engine` cannot both call theirs `Reported`.
 */
private data class NfcReported(val message: String, val level: LogLevel)

/** Serves a finite scripted bus, so a collection terminates. */
private class NfcFakeHost(
    private val events: List<TriggerEvent>,
    private val tags: Map<String, String> = emptyMap(),
    private val status: NfcStatus = NfcStatus.UNKNOWN,
) : TriggerHost {

    constructor(
        vararg events: TriggerEvent,
        tags: Map<String, String> = emptyMap(),
        status: NfcStatus = NfcStatus.UNKNOWN,
    ) : this(events.toList(), tags, status)

    val reported = mutableListOf<NfcReported>()

    override fun busEvents(): Flow<TriggerEvent> = events.asFlow()

    override fun busEventsFor(nodeId: NodeId): Flow<TriggerEvent> = busEvents()

    override fun nfcTag(uid: String): NfcTag? = tags[uid]?.let { NfcTag(uid = uid, name = it) }

    override fun nfcStatus(): NfcStatus = status

    override fun report(node: WorkflowNode, message: String, level: LogLevel) {
        reported += NfcReported(message, level)
    }

    override fun armSchedule(nodeId: NodeId, intervalMinutes: Long) = ScheduleHandle { }

    override fun armAlarm(nodeId: NodeId, atEpochMs: Long) = ScheduleHandle { }

    override fun armBatteryLevelPoll(
        nodeId: NodeId,
        intervalMinutes: Long,
        direction: com.example.ottomatic.engine.trigger.BatteryDirection,
        threshold: Int,
    ) = ScheduleHandle { }

    override fun armGeofence(
        nodeId: NodeId,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        transitions: Set<com.example.ottomatic.engine.trigger.GeofenceTransition>,
        dwellDelayMs: Int,
        onResult: (com.example.ottomatic.engine.trigger.GeofenceArmResult) -> Unit,
    ) = ScheduleHandle { }
}
