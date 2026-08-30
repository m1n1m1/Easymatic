package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
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
 * `action.launch_app` over the four ways a launch can end.
 *
 * The load-bearing assertion is that a launch Android **blocked** is reported as
 * blocked. That case is the whole reason this file exists: the platform drops a
 * background Activity start silently, so `startActivity` "succeeded", the node
 * reported nothing, and a macro that never opened anything left a clean console
 * behind it.
 */
class LaunchAppActionTest {

    private val services = RecordingSystemServices()
    private val entries = mutableListOf<LogEntry>()
    private val context = DefaultExecutionContext(systemServices = services) { entries += it }
    private val action = LaunchAppAction()

    private fun errors() = entries.filter { it.level == LogLevel.ERROR }.map { it.message }

    @Test
    fun `a chosen app is launched`() = runBlocking {
        action.execute(LaunchAppConfig(packageName = "com.google.android.apps.walletnfcrel"), context)

        assertEquals(listOf("com.google.android.apps.walletnfcrel"), services.launchedApps)
        assertTrue("a launch that worked has nothing to report", errors().isEmpty())
    }

    @Test
    fun `an unconfigured app launches nothing`() = runBlocking {
        action.execute(LaunchAppConfig(packageName = "   "), context)

        assertTrue("a blank package must never reach the platform", services.launchedApps.isEmpty())
        assertEquals(listOf("No app chosen"), errors())
    }

    @Test
    fun `an app that is not installed is named`() = runBlocking {
        services.launchOutcome = LaunchOutcome.NoSuchApp

        action.execute(LaunchAppConfig(packageName = "com.example.absent"), context)

        assertTrue(errors().single().contains("com.example.absent"))
    }

    /**
     * The regression test for the reported bug: a geofence macro wired to this
     * node opened nothing and said nothing.
     *
     * "Not installed" is the wrong story for a blocked launch — it sends the user
     * to the Play Store for an app that is right there — so the message must name
     * the permission instead.
     */
    @Test
    fun `a blocked launch is reported as blocked, not as missing`() = runBlocking {
        services.launchOutcome = LaunchOutcome.Blocked

        action.execute(LaunchAppConfig(packageName = "com.google.android.apps.walletnfcrel"), context)

        val message = errors().single()
        assertTrue("the message must name the grant that fixes it", message.contains("Display over other apps"))
        assertFalse("a blocked launch is not a missing app", message.contains("not installed"))
    }

    /**
     * Without this the node has no way to *offer* the grant: the amber card in the
     * config form is driven entirely by what the definition declares.
     */
    @Test
    fun `the node declares the overlay prerequisite`() {
        val definition = NodeTypeRegistry.byId(NodeTypeId("action.launch_app"))

        assertTrue(
            "Launch App must ask for the overlay grant, or a background launch fails with no way to fix it",
            definition?.permissionRequirements.orEmpty().any { it.type == PrerequisiteType.OVERLAY },
        )
    }
}
