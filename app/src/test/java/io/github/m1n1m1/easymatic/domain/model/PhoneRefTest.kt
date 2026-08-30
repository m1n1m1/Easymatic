package io.github.m1n1m1.easymatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRefTest {

    @Test
    fun `a contact spec round-trips`() {
        val spec = PhoneRef.contactSpec("0r3-2A44483C", "Mum")
        assertEquals(PhoneRef.Contact("0r3-2A44483C", "Mum"), PhoneRef.parse(spec))
    }

    /**
     * The encoding decision, pinned. A display name is arbitrary user text and a
     * lookup key is documented as opaque, so both are allowed to contain the
     * separator; only the key is encoded, and only because it comes first.
     */
    @Test
    fun `a display name containing the separator and escapes survives`() {
        val name = "Mum | Work: 100% sure"
        val spec = PhoneRef.contactSpec("0r3-2A", name)
        assertEquals(PhoneRef.Contact("0r3-2A", name), PhoneRef.parse(spec))
    }

    @Test
    fun `a lookup key containing a separator or a slash survives`() {
        val key = "0r3|2A/44%483C"
        val spec = PhoneRef.contactSpec(key, "Mum")
        assertEquals(PhoneRef.Contact(key, "Mum"), PhoneRef.parse(spec))
    }

    /**
     * The no-migration guarantee: every workflow written before contacts existed
     * holds a plain number, and a plain number is exactly what it means.
     */
    @Test
    fun `a legacy typed number parses to itself`() {
        assertEquals(PhoneRef.Literal("+436761234567"), PhoneRef.parse("+436761234567"))
        assertEquals(PhoneRef.Literal("0676 123 4567"), PhoneRef.parse("0676 123 4567"))
    }

    @Test
    fun `nothing chosen is null rather than a default`() {
        assertNull(PhoneRef.parse(""))
        assertNull(PhoneRef.parse("   "))
    }

    /** A truncated reference dials nothing rather than dialling its own text. */
    @Test
    fun `a malformed contact spec is null`() {
        assertNull(PhoneRef.parse("contact:"))
        assertNull(PhoneRef.parse("contact:|Mum"))
        assertNull(PhoneRef.parse("contact:0r3-2A"))
    }

    @Test
    fun `the same number written two ways matches`() {
        assertTrue(PhoneRef.matchesNumber("+436761234567", "0676 123 4567"))
        assertTrue(PhoneRef.matchesNumber("0676/1234567", "+43 676 1234567"))
    }

    @Test
    fun `two different numbers do not match`() {
        assertFalse(PhoneRef.matchesNumber("+436761234567", "+436761234568"))
        assertFalse(PhoneRef.matchesNumber("+436761234567", "+4915112345678"))
    }

    @Test
    fun `a number with no digits matches nothing`() {
        assertFalse(PhoneRef.matchesNumber("", "+436761234567"))
        assertFalse(PhoneRef.matchesNumber("+436761234567", ""))
        assertFalse(PhoneRef.matchesNumber("unknown", "+436761234567"))
    }

    /**
     * A short code is compared in full, so two unrelated ones do not collapse into
     * each other the way a fixed-length tail comparison would make them.
     */
    @Test
    fun `short numbers compare in full`() {
        assertTrue(PhoneRef.matchesNumber("112", "112"))
        assertFalse(PhoneRef.matchesNumber("112", "911"))
        assertFalse(PhoneRef.matchesNumber("4567", "1234567"))
    }
}
