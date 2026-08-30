package io.github.m1n1m1.easymatic.data

import io.github.m1n1m1.easymatic.core.service.AiModel
import io.github.m1n1m1.easymatic.data.security.FakeSecrets
import io.github.m1n1m1.easymatic.domain.model.AiModelProfile
import io.github.m1n1m1.easymatic.domain.model.AiProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The AI connection library.
 *
 * The interesting cases are the two [FakeSecrets] exists for, and both are states
 * no amount of correct code prevents: a phone restored from a backup, where the
 * file came across and the keystore key did not, and an OEM keystore that simply
 * refuses to seal. The third is the migration off the single-key layout this
 * replaced, which must not silently lose a key the user already pasted in.
 */
class AiConnectionRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val secrets = FakeSecrets()

    private fun repository() = AiConnectionRepository(folder.root, secrets)

    @Test
    fun `a fresh phone has no connections`() {
        assertTrue(repository().list().isEmpty())
    }

    @Test
    fun `a saved connection and its key survive a restart`() = runBlocking {
        val repository = repository()
        val created = repository.create("Personal", AiProvider.GEMINI)
        assertTrue(repository.setKey(created.id, "AIza-secret"))
        // A second instance over the same directory is what a process restart is.
        val reopened = repository()
        assertEquals("Personal", reopened.get(created.id)?.name)
        assertEquals("AIza-secret", reopened.apiKey(created.id))
    }

    /**
     * The whole reason this became a library: two keys with one provider is a real
     * setup, because quota is per key.
     */
    @Test
    fun `several connections coexist and keep their own keys`() = runBlocking {
        val repository = repository()
        val personal = repository.create("Personal", AiProvider.GEMINI)
        val work = repository.create("Work", AiProvider.GEMINI)
        repository.setKey(personal.id, "AIza-personal")
        repository.setKey(work.id, "AIza-work")
        assertEquals("AIza-personal", repository.apiKey(personal.id))
        assertEquals("AIza-work", repository.apiKey(work.id))
        assertEquals(2, repository.list().size)
    }

    @Test
    fun `what reaches disk is the sealed form and not the raw key`() = runBlocking {
        val repository = repository()
        val created = repository.create("Personal", AiProvider.GEMINI)
        repository.setKey(created.id, "AIza-secret")
        val onDisk = folder.root.resolve("ai/connections.json").readText()
        assertTrue(onDisk.contains(secrets.seal("AIza-secret")!!))
        assertFalse("the key must never be written unsealed", onDisk.contains("\"AIza-secret\""))
    }

    @Test
    fun `surrounding whitespace is trimmed off a pasted key`() = runBlocking {
        val repository = repository()
        val created = repository.create("Personal", AiProvider.GEMINI)
        repository.setKey(created.id, "  AIza-secret\n")
        assertEquals("AIza-secret", repository.apiKey(created.id))
    }

    /**
     * A restored phone: the library came across and the key that sealed it did not.
     * The row must report "paste it in again" rather than looking fine, since a
     * macro would otherwise fail at three in the morning with nothing saying why.
     */
    @Test
    fun `a key sealed under a lost keystore key reads as needing one`() = runBlocking {
        val created = repository().create("Personal", AiProvider.GEMINI)
        repository().setKey(created.id, "AIza-secret")
        secrets.openable = false
        val reopened = repository()
        assertTrue(reopened.needsKey(created.id))
        assertNull(reopened.apiKey(created.id))
        // The connection itself survives — only its key is gone.
        assertEquals("Personal", reopened.get(created.id)?.name)
    }

    @Test
    fun `a key that cannot be sealed is not stored and does not replace the old one`() = runBlocking {
        val repository = repository()
        val created = repository.create("Personal", AiProvider.GEMINI)
        repository.setKey(created.id, "AIza-first")
        secrets.sealable = false
        assertFalse(repository.setKey(created.id, "AIza-second"))
        assertEquals("AIza-first", repository.apiKey(created.id))
    }

    @Test
    fun `renaming a connection keeps its key and its id`() = runBlocking {
        val repository = repository()
        val created = repository.create("Personal", AiProvider.GEMINI)
        repository.setKey(created.id, "AIza-secret")
        repository.upsert(repository.get(created.id)!!.copy(name = "Renamed"))
        assertEquals("Renamed", repository.get(created.id)?.name)
        assertEquals("AIza-secret", repository.apiKey(created.id))
    }

    @Test
    fun `deleting a connection forgets it and its key`() = runBlocking {
        val repository = repository()
        val created = repository.create("Personal", AiProvider.GEMINI)
        repository.setKey(created.id, "AIza-secret")
        repository.delete(created.id)
        assertNull(repository.get(created.id))
        assertNull(repository.apiKey(created.id))
        assertTrue(repository().list().isEmpty())
    }

    @Test
    fun `setting a key on a connection that does not exist writes nothing`() = runBlocking {
        assertFalse(repository().setKey("no-such-id", "AIza-secret"))
    }

    /**
     * The single-key layout this library replaced stored one sealed key with no id.
     * Bringing it across costs the user nothing; failing to would ask them to go and
     * find a key again for a change they did not make.
     */
    @Test
    fun `a key stored by the old single-key layout is adopted as a connection`() = runBlocking {
        val sealed = secrets.seal("AIza-legacy")!!
        folder.root.resolve("ai").mkdirs()
        folder.root.resolve("ai/key.json").writeText("""{"secret":"$sealed"}""")

        val repository = repository()
        val adopted = repository.list().single()
        assertEquals("Gemini", adopted.name)
        assertEquals("AIza-legacy", repository.apiKey(adopted.id))
        // One-way: the old file is gone, so a connection deleted later cannot come back.
        assertFalse(folder.root.resolve("ai/key.json").exists())
    }

    @Test
    fun `an empty old key file adopts nothing rather than an unusable connection`() {
        folder.root.resolve("ai").mkdirs()
        folder.root.resolve("ai/key.json").writeText("""{"secret":""}""")
        assertTrue(repository().list().isEmpty())
    }

    @Test
    fun `a provider's address and its model profiles survive a restart`() = runBlocking {
        val repository = repository()
        val created = repository.create("Local", AiProvider.OPENAI_COMPATIBLE)
        repository.upsert(
            repository.get(created.id)!!.copy(
                baseUrl = "http://192.168.1.10:8000/v1",
                models = listOf(
                    AiModelProfile(
                        id = "profile-1",
                        name = "Household",
                        modelId = "Qwen/Qwen3-8B",
                        effort = AiModel.BALANCED,
                        systemPrompt = "Answer in German.",
                        tools = "action.notify",
                    ),
                ),
            ),
        )
        val reopened = repository().get(created.id)!!
        assertEquals(AiProvider.OPENAI_COMPATIBLE, reopened.provider)
        assertEquals("http://192.168.1.10:8000/v1", reopened.baseUrl)
        val profile = reopened.models.single()
        assertEquals("Household", profile.name)
        assertEquals("Qwen/Qwen3-8B", profile.modelId)
        assertEquals(AiModel.BALANCED, profile.effort)
        assertEquals("Answer in German.", profile.systemPrompt)
        assertEquals("action.notify", profile.tools)
    }

    /**
     * **An on-device account keeps no key and still round-trips**, together with the one
     * field only it uses.
     *
     * Both halves matter. The provider is persisted by *name*, so a library written by
     * this build has to survive a reopen with `ML_KIT` intact — an unknown name is a
     * discarded schema, not a migration. And `fallbackModelRef` is what makes an
     * unsupported phone usable at all, so a profile that lost it on a save would leave a
     * macro reporting a refusal where it used to answer.
     */
    @Test
    fun `an on-device connection and its fallback survive a restart`() = runBlocking {
        val repository = repository()
        val created = repository.create("On-device", AiProvider.ML_KIT)
        repository.upsert(
            repository.get(created.id)!!.copy(
                models = listOf(
                    AiModelProfile(id = "nano", name = "Nano", fallbackModelRef = "cloud-profile"),
                ),
            ),
        )

        val reopened = repository().get(created.id)!!
        assertEquals(AiProvider.ML_KIT, reopened.provider)
        assertEquals("cloud-profile", reopened.models.single().fallbackModelRef)
    }

    /**
     * A library written before the fallback field existed loads with it blank rather than
     * failing — the `ignoreUnknownKeys` + every-added-property-has-a-default invariant
     * that `AiConnection`'s KDoc states, checked for the newest property.
     */
    @Test
    fun `a profile written before the fallback field loads with none`() = runBlocking {
        folder.root.resolve("ai").mkdirs()
        folder.root.resolve("ai/connections.json").writeText(
            """
            [{"id":"c1","name":"Personal","provider":"GEMINI","secret":"",
              "models":[{"id":"p1","name":"Household","effort":"FAST"}]}]
            """.trimIndent(),
        )

        val profile = repository().get("c1")!!.models.single()
        assertEquals("Household", profile.name)
        assertEquals("", profile.fallbackModelRef)
    }

    /** Both halves of [AiConnectionRepository.resolve], which every AI node starts from. */
    @Test
    fun `a profile resolves to itself and to the account behind it`() = runBlocking {
        val repository = repository()
        val created = repository.create("Personal", AiProvider.GEMINI)
        repository.upsert(
            repository.get(created.id)!!.copy(models = listOf(AiModelProfile(id = "p1", name = "Household"))),
        )
        val (connection, profile) = repository.resolve("p1")!!
        assertEquals(created.id, connection.id)
        assertEquals("Household", profile.name)
        assertNull(repository.resolve("no-such-profile"))
    }

    /**
     * **The upconvert, which is the migration off the pre-profile layout.** One
     * standing prompt and three model-id overrides on the account become three named
     * profiles, so nothing the user configured is lost and no node has to be
     * re-pointed by hand.
     */
    @Test
    fun `a pre-profile connection becomes three named profiles`() {
        writeLegacyLibrary()
        val loaded = repository().list().single()

        assertEquals(listOf("Fast", "Balanced", "Thorough"), loaded.models.map { it.name })
        assertEquals(listOf(AiModel.FAST, AiModel.BALANCED, AiModel.THOROUGH), loaded.models.map { it.effort })
        assertEquals(listOf("qwen-small", "", "qwen-big"), loaded.models.map { it.modelId })
        // The persona was one field for all three, so all three keep it.
        assertTrue(loaded.models.all { it.systemPrompt == "Answer in German." })
    }

    /**
     * The ids are **derived from the connection id and the tier**, which is what lets
     * `repairAiRefs` repair a saved node as a pure function of that node's own config
     * — no library lookup, and no ordering between the two migrations.
     */
    @Test
    fun `a migrated profile's id is derived rather than generated`() {
        writeLegacyLibrary()
        val loaded = repository().list().single()
        assertEquals(AiModelProfile.legacyId("old-id", AiModel.BALANCED), loaded.models[1].id)
    }

    /** One-way and self-erasing, on `adoptSingleKey`'s model: a second read converts nothing. */
    @Test
    fun `the upconvert erases what it read and does not run twice`() {
        writeLegacyLibrary()
        val first = repository().list().single()
        assertEquals("", first.systemPrompt)
        assertEquals("", first.fastModel)

        val reopened = repository().list().single()
        assertEquals(first.models.map { it.id }, reopened.models.map { it.id })
        assertEquals(3, reopened.models.size)
    }

    /**
     * A connection with no overrides at all still gets three profiles, with blank
     * model ids — blank already meant "the provider's own model for this tier", so
     * that is the same three choices the user had before, now named.
     */
    @Test
    fun `a connection that overrode nothing still gets its three choices back`() {
        val sealed = secrets.seal("AIza-old")!!
        folder.root.resolve("ai").mkdirs()
        folder.root.resolve("ai/connections.json").writeText(
            """[{"id":"old-id","name":"Personal","provider":"GEMINI","secret":"$sealed"}]""",
        )
        val loaded = repository().list().single()
        assertEquals(3, loaded.models.size)
        assertTrue(loaded.models.all { it.modelId.isEmpty() })
    }

    /**
     * **The no-migration claim, pinned.** Every property added after the first
     * release defaults to blank, which is the whole reason a `connections.json`
     * written before they existed still loads: kotlinx fills an absent property from
     * its default and the repository decodes with `ignoreUnknownKeys`. A future
     * property without a default would break this test rather than somebody's phone.
     */
    @Test
    fun `a library file written before these fields existed still loads`() = runBlocking {
        val sealed = secrets.seal("AIza-old")!!
        folder.root.resolve("ai").mkdirs()
        folder.root.resolve("ai/connections.json").writeText(
            """[{"id":"old-id","name":"Personal","provider":"GEMINI","secret":"$sealed"}]""",
        )
        val repository = repository()
        val loaded = repository.list().single()
        assertEquals("Personal", loaded.name)
        assertEquals(AiProvider.GEMINI, loaded.provider)
        assertEquals("AIza-old", repository.apiKey("old-id"))
        assertEquals("", loaded.baseUrl)
    }

    /** A library in the shape the pre-profile build wrote: prompt and ids on the account. */
    private fun writeLegacyLibrary() {
        val sealed = secrets.seal("AIza-old")!!
        folder.root.resolve("ai").mkdirs()
        folder.root.resolve("ai/connections.json").writeText(
            """
            [{
              "id":"old-id","name":"Personal","provider":"GEMINI","secret":"$sealed",
              "systemPrompt":"Answer in German.",
              "fastModel":"qwen-small","thoroughModel":"qwen-big"
            }]
            """.trimIndent(),
        )
    }
}
