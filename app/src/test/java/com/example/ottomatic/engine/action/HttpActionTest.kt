package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.http` shares `action.open_url`'s reading of a URL field, so a bare host
 * is fetched over https here too.
 *
 * A non-web URL is refused **before** the request rather than surfacing as a
 * `MalformedURLException` dressed up as a network failure, and it lands on the
 * `response` port with the same `-1` the platform reports for a request that
 * could not be made — so nothing downstream has to learn a new shape.
 */
class HttpActionTest {

    private val services = RecordingSystemServices()
    private val context = DefaultExecutionContext(systemServices = services)
    private val action = HttpAction()

    @Test
    fun `a bare host is fetched over https`() = runBlocking {
        action.execute(HttpConfig(url = "api.github.com/zen"), context)
        assertEquals(listOf("https://api.github.com/zen"), services.httpRequests.map { it.url })
    }

    @Test
    fun `a full url is passed through untouched`() = runBlocking {
        action.execute(HttpConfig(url = "http://example.com/a"), context)
        assertEquals(listOf("http://example.com/a"), services.httpRequests.map { it.url })
    }

    @Test
    fun `a non-web url never reaches the network`() = runBlocking {
        val out = action.execute(HttpConfig(url = "mailto:someone@example.com"), context)
        assertTrue(services.httpRequests.isEmpty())
        assertEquals(-1, out.value.statusCode)
    }

    @Test
    fun `an unconfigured url never reaches the network`() = runBlocking {
        val out = action.execute(HttpConfig(url = ""), context)
        assertTrue(services.httpRequests.isEmpty())
        assertEquals(-1, out.value.statusCode)
        assertEquals("No URL set", out.value.body)
    }
}
