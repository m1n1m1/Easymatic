package com.example.ottomatic.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApiCallerRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun repository() = ApiCallerRepository(folder.root)

    @Test
    fun `an unknown package has no signer`() {
        assertNull(repository().signerFor("com.acme"))
    }

    @Test
    fun `an approval round-trips through the file`() {
        repository().approve("com.acme", "aa11", "Acme Tools", 1_700_000_000_000)
        val reread = repository()
        assertEquals("aa11", reread.signerFor("com.acme"))
        val caller = reread.approved.value.single()
        assertEquals("Acme Tools", caller.label)
        assertEquals(1_700_000_000_000, caller.approvedAtMs)
    }

    @Test
    fun `approving twice replaces rather than duplicates`() {
        val repository = repository()
        repository.approve("com.acme", "aa11", "Acme", 1)
        repository.approve("com.acme", "bb22", "Acme Renamed", 2)
        assertEquals(1, repository.approved.value.size)
        assertEquals("bb22", repository.signerFor("com.acme"))
    }

    @Test
    fun `revoking forgets the package`() {
        val repository = repository()
        repository.approve("com.acme", "aa11", "Acme", 1)
        repository.revoke("com.acme")
        assertNull(repository.signerFor("com.acme"))
        assertNull(repository().signerFor("com.acme"))
    }

    @Test
    fun `revoking one leaves the others`() {
        val repository = repository()
        repository.approve("com.acme", "aa11", "Acme", 1)
        repository.approve("com.other", "bb22", "Other", 2)
        repository.revoke("com.acme")
        assertEquals(listOf("com.other"), repository.approved.value.map { it.packageName })
    }

    @Test
    fun `an unreadable file reads as no approvals rather than throwing`() {
        // The one failure mode that must never take the process down: this is
        // constructed in ServiceLocator.init, before there is any UI to report to.
        folder.newFolder("api").resolve("callers.json").writeText("{ not json")
        assertEquals(emptyList<ApprovedCaller>(), repository().approved.value)
    }
}
