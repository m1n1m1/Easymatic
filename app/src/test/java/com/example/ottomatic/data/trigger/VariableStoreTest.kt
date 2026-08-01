package com.example.ottomatic.data.trigger

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The store behind `action.set_variable`, `value.variable` and
 * `trigger.variable_change` — the only thing in the app that outlives a run.
 */
class VariableStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val job = Job()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    @Before
    fun reset() = VariableStore.clear()

    @After
    fun tearDown() {
        job.cancel()
        VariableStore.clear()
    }

    @Test
    fun `an unset variable reads as null rather than empty`() {
        assertNull(VariableStore.get("nothing"))
    }

    @Test
    fun `variables survive the process`() = runBlocking {
        val directory = folder.newFolder()
        VariableStore.attach(directory, scope)
        VariableStore.set("counter", "7")
        awaitWrites()

        // A cold start: the same directory, a store that remembers nothing.
        VariableStore.clear()
        VariableStore.attach(directory, scope)
        assertEquals("7", VariableStore.get("counter"))
    }

    @Test
    fun `the latest write is the one that survives`() = runBlocking {
        val directory = folder.newFolder()
        VariableStore.attach(directory, scope)
        VariableStore.set("counter", "1")
        VariableStore.set("counter", "2")
        VariableStore.set("counter", "3")
        awaitWrites()

        VariableStore.clear()
        VariableStore.attach(directory, scope)
        assertEquals("3", VariableStore.get("counter"))
    }

    @Test
    fun `an unattached store still works, it just forgets`() {
        // Engine-only tests and previews never call attach.
        VariableStore.set("counter", "1")
        assertEquals("1", VariableStore.get("counter"))
    }

    @Test
    fun `a corrupt file reads as no variables rather than crashing the app`() {
        val directory = folder.newFolder()
        File(directory, "variables.json").writeText("{ this is not json")
        VariableStore.attach(directory, scope)
        assertNull(VariableStore.get("counter"))
    }

    @Test
    fun `a change is announced, and rewriting the same value is silent`() = runBlocking {
        // Load-bearing rather than an optimisation: `trigger.variable_change`
        // means "when this changes", so a macro rewriting a variable on a timer
        // must not fire it every tick. Seeing "2" arrive second is what proves
        // the repeated "1" emitted nothing.
        VariableStore.attach(folder.newFolder(), scope)
        val subscribed = CompletableDeferred<Unit>()
        val received = Channel<String>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) {
            VariableStore.changes
                .onSubscription { subscribed.complete(Unit) }
                .collect { received.trySend(it.payload["value"].orEmpty()) }
        }
        subscribed.await()

        VariableStore.set("counter", "1")
        VariableStore.set("counter", "1")
        VariableStore.set("counter", "2")

        assertEquals("1", withTimeout(TIMEOUT_MS) { received.receive() })
        assertEquals("2", withTimeout(TIMEOUT_MS) { received.receive() })
        collector.cancel()
    }

    @Test
    fun `an event carries the name the trigger filters on`() = runBlocking {
        // `changesFor` — and therefore `trigger.variable_change` — is a filter on
        // this payload key, so the name has to be on the event itself.
        VariableStore.attach(folder.newFolder(), scope)
        val subscribed = CompletableDeferred<Unit>()
        val received = Channel<String>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) {
            VariableStore.changes
                .onSubscription { subscribed.complete(Unit) }
                .collect { received.trySend(it.payload["name"].orEmpty()) }
        }
        subscribed.await()

        VariableStore.set("ignored", "no")
        VariableStore.set("wanted", "yes")

        assertEquals("ignored", withTimeout(TIMEOUT_MS) { received.receive() })
        assertEquals("wanted", withTimeout(TIMEOUT_MS) { received.receive() })
        collector.cancel()
    }

    /** Waits for the background file writes [scope] has been given. */
    private suspend fun awaitWrites() {
        withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.children.forEach { it.join() } }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
