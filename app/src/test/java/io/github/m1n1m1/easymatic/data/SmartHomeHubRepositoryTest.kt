package io.github.m1n1m1.easymatic.data

import io.github.m1n1m1.easymatic.core.service.SmartHomeTargetKind
import io.github.m1n1m1.easymatic.data.security.FakeSecrets
import io.github.m1n1m1.easymatic.domain.model.HubAuthMode
import io.github.m1n1m1.easymatic.domain.model.SmartHomeHub
import io.github.m1n1m1.easymatic.domain.model.SmartHomeKind
import io.github.m1n1m1.easymatic.domain.model.SmartHomeResource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The hub library on disk.
 *
 * Most of these are about the credential rather than the file, because that is where
 * the failures are: a key that will not seal, a key that will not open, and the two
 * updates that must be able to happen without disturbing it.
 */
class SmartHomeHubRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val secrets = FakeSecrets()

    private fun repository() = SmartHomeHubRepository(folder.root, secrets)

    private fun hub(name: String = "Bridge") = SmartHomeHub(
        id = "",
        name = name,
        host = "192.168.1.42",
        certSha256 = "AA11",
    )

    @Test
    fun `a hub survives a reload`() = runBlocking {
        val created = repository().create(hub("Living room"))

        // A second instance, so this is a real file round trip rather than a cache.
        assertEquals("Living room", repository().get(created.id)?.name)
        assertEquals("192.168.1.42", repository().get(created.id)?.host)
    }

    @Test
    fun `hubs are listed by name whatever case they were typed in`() = runBlocking {
        val repository = repository()
        repository.create(hub("zebra"))
        repository.create(hub("Attic"))

        assertEquals(listOf("Attic", "zebra"), repository.list().map { it.name })
    }

    @Test
    fun `the application key round trips but never as plaintext on disk`() = runBlocking {
        val repository = repository()
        val created = repository.create(hub())

        assertTrue(repository.setKeys(created.id, "app-key", "stream-key"))
        assertEquals("app-key", repository.applicationKey(created.id))
        assertFalse(repository.get(created.id)!!.secret.contains("app-key") &&
            repository.get(created.id)!!.secret == "app-key")
        assertTrue(repository.get(created.id)!!.streamSecret.isNotBlank())
    }

    /**
     * What a **restored phone** looks like: the hub file came across from a backup
     * and the key that sealed its credential did not. The library must degrade to
     * "pair it again" rather than losing the hub, which is also why the file is
     * deliberately left in the backup set.
     */
    @Test
    fun `a key this device cannot open reads as needing to be paired again`() = runBlocking {
        val repository = repository()
        val created = repository.create(hub())
        repository.setKeys(created.id, "app-key")
        assertFalse(repository.needsPairing(created.id))

        secrets.openable = false

        assertNull(repository.applicationKey(created.id))
        assertTrue(repository.needsPairing(created.id))
        // The hub itself is still there, with its address and its name.
        assertEquals("192.168.1.42", repository.get(created.id)?.host)
    }

    /**
     * A keystore hiccup must leave a working credential in place rather than
     * replacing it with something unreadable, so nothing is written when sealing
     * fails.
     */
    @Test
    fun `a refused seal writes nothing at all`() = runBlocking {
        val repository = repository()
        val created = repository.create(hub())
        repository.setKeys(created.id, "good-key")

        secrets.sealable = false

        assertFalse(repository.setKeys(created.id, "new-key"))
        assertEquals("good-key", repository.applicationKey(created.id))
    }

    /**
     * The two updates that happen on their own: a snapshot refresh and a re-trust
     * after a firmware update rotated the certificate. Either one clobbering the key
     * would turn a routine event into a walk to the bridge.
     */
    @Test
    fun `refreshing resources and re-pinning leave the key alone`() = runBlocking {
        val repository = repository()
        val created = repository.create(hub())
        repository.setKeys(created.id, "app-key")

        repository.setResources(
            created.id,
            listOf(SmartHomeResource(SmartHomeTargetKind.LIGHT, "rid-1", "Lamp")),
            refreshedAtEpochMs = 1_234,
        )
        repository.setCertificate(created.id, "BB22")

        val stored = repository.get(created.id)!!
        assertEquals("app-key", repository.applicationKey(created.id))
        assertEquals("BB22", stored.certSha256)
        assertEquals(1, stored.resources.size)
        assertEquals(1_234, stored.resourcesRefreshedAtEpochMs)
    }

    @Test
    fun `a hub that was never finished pairing is not complete`() = runBlocking {
        val repository = repository()
        val created = repository.create(hub())

        assertFalse(repository.get(created.id)!!.isComplete)
        repository.setKeys(created.id, "app-key")
        assertTrue(repository.get(created.id)!!.isComplete)
    }

    /**
     * The line that would have made every Home Assistant hub read as "never finished
     * pairing", everywhere in the app, while being perfectly set up: a Hue bridge is
     * complete only with a pinned certificate, and a Home Assistant instance can never
     * have one — it is reached over plain HTTP on the LAN, or over a certificate the
     * platform verifies for itself.
     */
    @Test
    fun `a Home Assistant hub is complete without a certificate`() = runBlocking {
        val repository = repository()
        val created = repository.create(
            SmartHomeHub(
                id = "",
                kind = SmartHomeKind.HOME_ASSISTANT,
                name = "Home Assistant",
                host = "http://homeassistant.local:8123",
                authMode = HubAuthMode.TOKEN,
            ),
        )

        assertFalse(repository.get(created.id)!!.isComplete)
        repository.setKeys(created.id, "long-lived-token")

        val stored = repository.get(created.id)!!
        assertTrue(stored.isComplete)
        assertEquals("", stored.certSha256)
    }

    /**
     * An OAuth hub whose access token expired while the phone was off is **complete**,
     * not broken: the refresh token is what the transport renews from before anything
     * is sent. Reading it as incomplete would send the user to sign in again over
     * something the app fixes by itself.
     */
    @Test
    fun `an OAuth hub with only a refresh token is complete`() {
        val hub = SmartHomeHub(
            id = "h",
            kind = SmartHomeKind.HOME_ASSISTANT,
            name = "Home Assistant",
            host = "http://homeassistant.local:8123",
            authMode = HubAuthMode.OAUTH,
            refreshSecret = "sealed-refresh",
        )

        assertTrue(hub.isComplete)
    }

    @Test
    fun `deleting removes it`() = runBlocking {
        val repository = repository()
        val created = repository.create(hub())

        repository.delete(created.id)

        assertNull(repository.get(created.id))
        assertTrue(repository().list().isEmpty())
    }

    /**
     * Losing the hubs is bad; crashing on startup is worse, and this repository is
     * constructed from `Application.onCreate`.
     */
    @Test
    fun `a corrupt file reads as an empty library rather than throwing`() {
        folder.newFolder("smarthome")
        folder.newFile("smarthome/hubs.json").writeText("{ this is not json")

        assertTrue(repository().list().isEmpty())
    }
}
