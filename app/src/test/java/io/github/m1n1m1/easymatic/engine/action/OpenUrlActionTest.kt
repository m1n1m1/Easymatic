package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.service.LaunchOutcome
import io.github.m1n1m1.easymatic.core.service.LogEntry
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.open_url` over the shapes a URL field actually holds.
 *
 * The load-bearing assertions are that the platform receives the **normalized**
 * URL rather than what was typed, and that text which is not a URL reaches it
 * **not at all** — an intent aimed at nothing fails the same way a missing
 * browser does, so it must never be launched.
 */
class OpenUrlActionTest {

    private val services = RecordingSystemServices()
    private val context = DefaultExecutionContext(systemServices = services)
    private val action = OpenUrlAction()

    @Test
    fun `a bare host is opened over https`() = runBlocking {
        action.execute(OpenUrlConfig(url = "google.com"), context)
        assertEquals(listOf("https://google.com"), services.openedUrls)
    }

    @Test
    fun `www needs no scheme either`() = runBlocking {
        action.execute(OpenUrlConfig(url = "www.google.com/maps"), context)
        assertEquals(listOf("https://www.google.com/maps"), services.openedUrls)
    }

    @Test
    fun `a full url is passed through untouched`() = runBlocking {
        action.execute(OpenUrlConfig(url = "http://example.com/a?b=c"), context)
        assertEquals(listOf("http://example.com/a?b=c"), services.openedUrls)
    }

    @Test
    fun `a deep link is not mistaken for a host`() = runBlocking {
        action.execute(OpenUrlConfig(url = "mailto:someone@example.com"), context)
        assertEquals(listOf("mailto:someone@example.com"), services.openedUrls)
    }

    @Test
    fun `an unconfigured url opens nothing`() = runBlocking {
        action.execute(OpenUrlConfig(url = "   "), context)
        assertTrue(services.openedUrls.isEmpty())
    }

    @Test
    fun `text that is not a url opens nothing`() = runBlocking {
        action.execute(OpenUrlConfig(url = "hello world"), context)
        assertTrue("a sentence must never be launched as an intent", services.openedUrls.isEmpty())
    }

    /**
     * The URL half of the same defect as `action.launch_app`: Android drops a
     * background Activity start silently, so this used to pass cleanly.
     */
    @Test
    fun `a url Android blocked is reported as blocked`() = runBlocking {
        val entries = mutableListOf<LogEntry>()
        val blocked = RecordingSystemServices().apply { launchOutcome = LaunchOutcome.Blocked }
        val context = DefaultExecutionContext(systemServices = blocked) { entries += it }

        OpenUrlAction().execute(OpenUrlConfig(url = "google.com"), context)

        val message = entries.single { it.level == LogLevel.ERROR }.message
        assertTrue("the message must name the grant that fixes it", message.contains("Display over other apps"))
    }

    /**
     * A URL arriving over a wire is normalized exactly as one typed into the
     * form: `decode` picks the wired value first and normalization happens after,
     * so the rule is stated once and holds wherever the value came from.
     */
    @Test
    fun `a bare host arriving over a wire is normalized too`() = runBlocking {
        val config = action.definition.schema.decode(
            config = emptyMap(),
            data = mapOf(PortName("url") to Item.of("google.com")),
        )
        action.execute(config, context)
        assertEquals(listOf("https://google.com"), services.openedUrls)
    }
}
