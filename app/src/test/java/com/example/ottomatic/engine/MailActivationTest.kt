package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.MailAccount
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.MailMessage
import com.example.ottomatic.engine.trigger.MailPayload
import com.example.ottomatic.engine.trigger.MailTrigger
import com.example.ottomatic.engine.trigger.MailTriggerConfig
import com.example.ottomatic.engine.trigger.MailWatchSpec
import com.example.ottomatic.engine.trigger.ScheduleHandle
import com.example.ottomatic.engine.trigger.TriggerHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `trigger.mail` watches, what it lets through, and what it says when it
 * cannot watch anything at all.
 *
 * [MailFakeHost] serves a finite bus, so collecting the trigger's flow completes
 * once the scripted arrivals have been delivered rather than waiting forever.
 */
class MailActivationTest {

    private val node = WorkflowNode(NodeId("n1"), NodeTypeId("trigger.mail"), "Mail", 0f, 0f)

    private val invoice = arrival(from = "billing@shop.example", subject = "Your invoice")
    private val newsletter = arrival(from = "news@list.example", subject = "This week")

    @Test
    fun `an unfiltered trigger runs on every arrival`() = runBlocking {
        val emitted = collect(MailTriggerConfig(accountId = ACCOUNT), invoice, newsletter)

        assertEquals(listOf("Your invoice", "This week"), emitted.map { it.subject })
    }

    @Test
    fun `a sender filter excludes everything else`() = runBlocking {
        val emitted = collect(
            MailTriggerConfig(accountId = ACCOUNT, fromContains = "billing@"),
            invoice,
            newsletter,
        )

        assertEquals(listOf("Your invoice"), emitted.map { it.subject })
    }

    @Test
    fun `a subject filter excludes everything else`() = runBlocking {
        val emitted = collect(
            MailTriggerConfig(accountId = ACCOUNT, subjectContains = "INVOICE"),
            invoice,
            newsletter,
        )

        assertEquals(listOf("Your invoice"), emitted.map { it.subject })
    }

    /**
     * The sender is matched on the address as well as the display name, and the
     * address is the half that matters: a display name is chosen by whoever sent
     * the mail, so it is the part an unwanted sender controls.
     */
    @Test
    fun `the display name is matched too`() = runBlocking {
        val emitted = collect(
            MailTriggerConfig(accountId = ACCOUNT, fromContains = "Shop Billing"),
            arrival(from = "billing@shop.example", subject = "Hi", fromName = "Shop Billing"),
        )

        assertEquals(1, emitted.size)
    }

    /**
     * Only what is needed to open a connection crosses into the arm.
     * `fromContains` is a predicate the trigger applies per event, so editing it
     * must not require a re-arm — `trigger.sms`'s call about its sender filter.
     * `unreadOnly` is the exception, because the server evaluates it.
     */
    @Test
    fun `the arm carries the connection settings and not the filters`() = runBlocking {
        val host = MailFakeHost()

        MailTrigger().activate(
            MailTriggerConfig(
                accountId = ACCOUNT,
                folder = "Archive",
                fromContains = "billing@",
                unreadOnly = false,
            ),
            node,
            host,
        ).toList()

        val spec = host.armedWith.single()
        assertEquals("Archive", spec.folder)
        assertFalse(spec.unreadOnly)
    }

    @Test
    fun `the watch is torn down when the flow ends`() = runBlocking {
        val host = MailFakeHost(invoice)

        MailTrigger().activate(MailTriggerConfig(accountId = ACCOUNT), node, host).toList()

        assertEquals(1, host.cancelled)
    }

    /**
     * Without an account there is nothing to connect to — unlike a missing tag,
     * which costs a firing macro only its friendly name. So this stays unarmed and
     * says so, the way an unresolvable geofence place does: a macro that looks
     * armed and is not is the failure `report` exists for.
     */
    @Test
    fun `an unchosen account leaves the trigger unarmed and says so`() = runBlocking {
        val host = MailFakeHost(invoice, account = null)

        val emitted = MailTrigger().activate(MailTriggerConfig(accountId = ""), node, host).toList()

        assertTrue(emitted.isEmpty())
        assertTrue(host.armedWith.isEmpty())
        val warning = host.reported.single { it.level == LogLevel.WARN }
        assertTrue(warning.message, warning.message.contains("No mail account chosen"))
    }

    @Test
    fun `a deleted account is reported differently from an unchosen one`() = runBlocking {
        val host = MailFakeHost(account = null)

        MailTrigger().activate(MailTriggerConfig(accountId = "gone"), node, host).toList()

        val warning = host.reported.single { it.level == LogLevel.WARN }
        assertTrue(warning.message, warning.message.contains("deleted"))
    }

    /** Every payload key survives the trip onto the item. */
    @Test
    fun `the whole message is carried across the bus`() = runBlocking {
        val emitted = collect(MailTriggerConfig(accountId = ACCOUNT), invoice)

        val message = emitted.single()
        assertEquals("mail:acc-1|9|42|INBOX", message.ref)
        assertEquals("billing@shop.example", message.from)
        assertEquals("Your invoice", message.subject)
        assertEquals("Body text", message.body)
        assertEquals("INBOX", message.folder)
        assertEquals(ACCOUNT, message.accountId)
        assertEquals(1_700_000_000_000L, message.receivedAt.epochMs)
    }

    private suspend fun collect(
        config: MailTriggerConfig,
        vararg events: TriggerEvent,
    ): List<MailMessage> =
        MailTrigger().activate(config, node, MailFakeHost(*events)).toList().map { it.value }

    private fun arrival(from: String, subject: String, fromName: String = "") = TriggerEvent(
        source = TriggerSource.MAIL,
        triggerNodeId = NodeId("n1"),
        payload = mapOf(
            MailPayload.REF to "mail:acc-1|9|42|INBOX",
            MailPayload.FROM to from,
            MailPayload.FROM_NAME to fromName,
            MailPayload.SUBJECT to subject,
            MailPayload.BODY to "Body text",
            MailPayload.FOLDER to "INBOX",
            MailPayload.ACCOUNT_ID to ACCOUNT,
            MailPayload.RECEIVED_AT to "1700000000000",
        ),
    )

    private companion object {
        const val ACCOUNT = "acc-1"
    }
}

/** See `NfcActivationTest`'s note on why these helpers are named per file. */
private data class MailReported(val message: String, val level: LogLevel)

/** Serves a finite scripted bus, so a collection terminates. */
private class MailFakeHost(
    private val events: List<TriggerEvent>,
    private val account: MailAccount? = DEFAULT_ACCOUNT,
) : TriggerHost {

    constructor(
        vararg events: TriggerEvent,
        account: MailAccount? = DEFAULT_ACCOUNT,
    ) : this(events.toList(), account)

    val reported = mutableListOf<MailReported>()
    val armedWith = mutableListOf<MailWatchSpec>()
    var cancelled = 0

    override fun busEvents(): Flow<TriggerEvent> = events.asFlow()

    override fun busEventsFor(nodeId: NodeId): Flow<TriggerEvent> = busEvents()

    override fun mailAccount(id: String): MailAccount? = account

    override fun armMailWatch(nodeId: NodeId, accountId: String, spec: MailWatchSpec): ScheduleHandle {
        armedWith += spec
        return ScheduleHandle { cancelled++ }
    }

    override fun report(node: WorkflowNode, message: String, level: LogLevel) {
        reported += MailReported(message, level)
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

    private companion object {
        val DEFAULT_ACCOUNT = MailAccount(
            id = "acc-1",
            name = "Work",
            address = "me@example.com",
            smtpHost = "smtp.example.com",
            imapHost = "imap.example.com",
        )
    }
}
