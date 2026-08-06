package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.Contacts
import com.example.ottomatic.core.service.LaunchOutcome
import com.example.ottomatic.domain.model.PhoneRef
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An address book holding exactly one person. */
private class FakeContacts(private val numbers: Map<String, String>) : Contacts {
    override fun phoneNumber(lookupKey: String): String? = numbers[lookupKey]
}

/**
 * `action.call` and `action.send_sms` over the two things a phone field can hold.
 *
 * The load-bearing assertions are that an unresolvable contact reaches
 * [RecordingSystemServices] **not at all** — a spec must never be dialled as if it
 * were a number — and that the data port carries the resolved number rather than
 * the spec, so a `contact:` reference cannot leak onto a wire.
 */
class PhoneActionsTest {

    private val services = RecordingSystemServices()
    private val mum = PhoneRef.contactSpec("0r3-2A", "Mum")

    private fun context(contacts: Contacts = FakeContacts(mapOf("0r3-2A" to "+436761234567"))) =
        DefaultExecutionContext(systemServices = services, contacts = contacts)

    @Test
    fun `a typed number is called as it stands`() = runBlocking {
        val out = CallAction().execute(CallConfig(number = "+436761234567"), context())
        assertEquals(listOf("+436761234567"), services.calls)
        assertTrue(out.value.initiated)
        assertEquals("+436761234567", out.value.number)
    }

    @Test
    fun `a contact is resolved to its current number`() = runBlocking {
        val out = CallAction().execute(CallConfig(number = mum), context())
        assertEquals(listOf("+436761234567"), services.calls)
        assertEquals("+436761234567", out.value.number)
    }

    @Test
    fun `an unresolvable contact dials nothing`() = runBlocking {
        val out = CallAction().execute(CallConfig(number = mum), context(FakeContacts(emptyMap())))
        assertTrue("a spec must never be dialled as if it were a number", services.calls.isEmpty())
        assertFalse(out.value.initiated)
        assertEquals("", out.value.number)
    }

    @Test
    fun `an unconfigured number dials nothing`() = runBlocking {
        val out = CallAction().execute(CallConfig(number = ""), context())
        assertTrue(services.calls.isEmpty())
        assertFalse(out.value.initiated)
    }

    @Test
    fun `an sms to a contact goes to the resolved number`() = runBlocking {
        val out = SendSmsAction().execute(SendSmsConfig(to = mum, body = "hi"), context())
        assertEquals(listOf("+436761234567" to "hi"), services.smsSent)
        assertEquals("+436761234567", out.value.to)
        assertTrue(out.value.sent)
    }

    @Test
    fun `an sms to an unresolvable contact sends nothing`() = runBlocking {
        val out = SendSmsAction().execute(
            SendSmsConfig(to = mum, body = "hi"),
            context(FakeContacts(emptyMap())),
        )
        assertTrue(services.smsSent.isEmpty())
        assertFalse(out.value.sent)
    }

    /**
     * On an unattended macro this is the worst of the three blocked launches — a
     * "call for help" node that dialled nothing and reported success.
     */
    @Test
    fun `a call Android blocked does not report itself as initiated`() = runBlocking {
        services.launchOutcome = LaunchOutcome.Blocked

        val out = CallAction().execute(CallConfig(number = "+436761234567"), context())

        assertFalse("a blocked dial must not claim it happened", out.value.initiated)
    }

    /**
     * A spec arriving over a wire resolves exactly as one typed into the form.
     * `decode` picks the wired value first, and resolution happens after — so the
     * rule is stated once and holds wherever the value came from.
     */
    @Test
    fun `a contact spec arriving over a wire resolves too`() = runBlocking {
        val schema = CallAction().definition.schema
        val config = schema.decode(
            config = emptyMap(),
            data = mapOf(PortName("number") to Item.of(mum)),
        )
        CallAction().execute(config, context())
        assertEquals(listOf("+436761234567"), services.calls)
    }
}
