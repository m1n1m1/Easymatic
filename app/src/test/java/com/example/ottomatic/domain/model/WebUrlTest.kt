package com.example.ottomatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reading of a URL field, which is deliberately lenient about the scheme and
 * deliberately strict about everything else.
 *
 * The load-bearing cases are the two ends: a bare host **gains** a scheme — the
 * thing that used to fail and looked like a broken node — while anything that
 * already names one is handed on **untouched**, so `mailto:`, `tel:` and app deep
 * links keep working exactly as they did before normalization existed.
 */
class WebUrlTest {

    @Test
    fun `a bare host is opened over https`() {
        assertEquals("https://google.com", WebUrl.normalize("google.com"))
        assertEquals("https://www.google.com", WebUrl.normalize("www.google.com"))
    }

    @Test
    fun `a bare host keeps its path, query and fragment`() {
        assertEquals("https://google.com/maps?q=1#top", WebUrl.normalize("google.com/maps?q=1#top"))
    }

    @Test
    fun `an address with a port is a host, not a scheme`() {
        assertEquals("https://192.168.1.5:8080/api", WebUrl.normalize("192.168.1.5:8080/api"))
        assertEquals("https://localhost:3000", WebUrl.normalize("localhost:3000"))
    }

    @Test
    fun `localhost needs no dot and an IPv6 literal needs no host name`() {
        assertEquals("https://localhost", WebUrl.normalize("localhost"))
        assertEquals("https://[::1]:8080", WebUrl.normalize("[::1]:8080"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("https://google.com", WebUrl.normalize("  google.com\n"))
    }

    @Test
    fun `a protocol-relative url only gains the scheme name`() {
        assertEquals("https://google.com/a", WebUrl.normalize("//google.com/a"))
    }

    @Test
    fun `anything that already names a scheme is untouched`() {
        val untouched = listOf(
            "https://example.com",
            "http://example.com/a?b=c",
            "mailto:someone@example.com",
            "tel:+4312345",
            "geo:47.07,15.44",
            "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
            "myapp://open/thing",
        )
        untouched.forEach { assertEquals(it, WebUrl.normalize(it)) }
    }

    @Test
    fun `text that is not a url is refused`() {
        listOf("", "   ", "hello", "hello world", "Battery is at 43%", ".com", "google.")
            .forEach { assertNull("\"$it\" is not a URL", WebUrl.normalize(it)) }
    }

    @Test
    fun `webOnly accepts a bare host and refuses a non-web scheme`() {
        assertEquals("https://api.github.com/zen", WebUrl.webOnly("api.github.com/zen"))
        assertEquals("http://example.com", WebUrl.webOnly("http://example.com"))
        assertNull(WebUrl.webOnly("mailto:someone@example.com"))
        assertNull(WebUrl.webOnly("tel:+4312345"))
        assertNull(WebUrl.webOnly("myapp://open/thing"))
    }
}
