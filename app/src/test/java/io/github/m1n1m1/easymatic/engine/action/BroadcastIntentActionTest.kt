package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.service.IntentTarget
import io.github.m1n1m1.easymatic.core.service.LaunchOutcome
import io.github.m1n1m1.easymatic.core.service.LogEntry
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.broadcast_intent` — the half of the intent pair that cannot find out whether it worked.
 *
 * Two assertions here are anti-regressions rather than behaviour checks, and both guard a
 * deliberate asymmetry somebody would otherwise tidy away: this node declares **no** permission,
 * and `LaunchOutcome.NoReceiver` is reported at WARN rather than ERROR. Making either one match
 * its sibling would be wrong, and neither is a compile error.
 */
class BroadcastIntentActionTest {

    private val services = RecordingSystemServices()
    private val entries = mutableListOf<LogEntry>()
    private val context = DefaultExecutionContext(systemServices = services) { entries += it }
    private val action = BroadcastIntentAction()

    private fun errors() = entries.filter { it.level == LogLevel.ERROR }.map { it.message }

    private fun warnings() = entries.filter { it.level == LogLevel.WARN }.map { it.message }

    @Test
    fun `a broadcast goes out as a broadcast`() = runBlocking {
        action.execute(
            IntentConfig(action = "com.example.DO_THING", packageName = "com.example.target"),
            context,
        )

        val recipe = services.sentIntents.single()
        assertEquals(IntentTarget.BROADCAST, recipe.target)
        assertEquals("com.example.DO_THING", recipe.action)
        assertTrue(errors().isEmpty())
        assertTrue("naming an app is the normal case and earns no warning", warnings().isEmpty())
    }

    /**
     * Android 8 stopped delivering implicit broadcasts to manifest-declared receivers, which is
     * what most of them are — so this is usually a mistake. It is still *sent*, because a
     * receiver registered in code at runtime is a legitimate target and is reached perfectly
     * well; refusing would break the case that works.
     */
    @Test
    fun `a broadcast with no app named is warned about and sent anyway`() = runBlocking {
        action.execute(IntentConfig(action = "com.example.DO_THING"), context)

        assertEquals(1, services.sentIntents.size)
        assertTrue(warnings().single().contains("Android 8"))
        assertTrue("a warning is not a failure", errors().isEmpty())
    }

    /**
     * The one outcome in `reportLaunch`'s table that is not a failure. The broadcast was sent;
     * we merely suspect nobody was listening, and that suspicion is assembled from a query that
     * cannot see runtime-registered receivers. Reporting it as an error would make the one
     * outcome that is guessed indistinguishable from the four that are known.
     */
    @Test
    fun `a broadcast nobody seems to hear is a warning, not an error`() = runBlocking {
        services.launchOutcome = LaunchOutcome.NoReceiver

        action.execute(
            IntentConfig(action = "com.example.TYPOED", packageName = "com.example.target"),
            context,
        )

        assertTrue(warnings().any { it.contains("com.example.TYPOED") })
        assertTrue("the broadcast was sent — that is not an error", errors().isEmpty())
    }

    @Test
    fun `a protected broadcast says so without offering a grant that cannot help`() = runBlocking {
        services.launchOutcome = LaunchOutcome.Refused

        action.execute(IntentConfig(action = "android.intent.action.BOOT_COMPLETED"), context)

        val message = errors().single()
        assertTrue(message.contains("android.intent.action.BOOT_COMPLETED"))
        assertFalse(
            "no permission makes a protected broadcast sendable, so naming one misleads",
            message.contains("Display over other apps"),
        )
    }

    /**
     * The anti-regression for the entire two-nodes-rather-than-one design. A broadcast is under
     * no background-start rule, so declaring the overlay grant here would badge the node in the
     * Problems panel for a grant that changes nothing — badging it for working.
     */
    @Test
    fun `the node declares no permissions`() {
        val definition = NodeTypeRegistry.byId(NodeTypeId("action.broadcast_intent"))

        assertEquals(
            "Broadcasting needs no grant; declaring one for symmetry with Send Intent warns about nothing",
            emptyList<Any>(),
            definition?.permissionRequirements.orEmpty(),
        )
    }
}
