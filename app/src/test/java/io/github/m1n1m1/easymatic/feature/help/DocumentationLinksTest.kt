package io.github.m1n1m1.easymatic.feature.help

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentationLinksTest {
    @Test
    fun `documentation pages anchors and search stay inside the viewer`() {
        listOf(
            DOCUMENTATION_URL,
            "https://easymatic.app/docs/start/editor/",
            "https://easymatic.app/docs/?search=macro#node-reference",
            "https://EASYMATIC.APP:443/privacy/",
        ).forEach { assertEquals(it, DocumentationLink.INTERNAL, documentationLink(it)) }
    }

    @Test
    fun `external sites mail and lookalike hosts leave the viewer`() {
        listOf(
            "https://github.com/m1n1m1/Easymatic",
            "mailto:help@example.com",
            "http://easymatic.app/docs/",
            "https://easymatic.app.evil.example/docs/",
            "https://easymatic.app@evil.example/docs/",
            "https://evil.example@easymatic.app/docs/",
            "https://easymatic.app:8443/docs/",
        ).forEach { assertEquals(it, DocumentationLink.EXTERNAL, documentationLink(it)) }
    }

    @Test
    fun `local content executable schemes and malformed links are blocked`() {
        listOf(
            "file:///data/user/0/private.txt",
            "content://private/items/1",
            "javascript:alert(1)",
            "data:text/html,test",
            "intent://launch/#Intent;end",
            "https:///docs/",
            "https://easy matic.app/docs/",
            "https://easymatic.app\\@evil.example/",
            "",
        ).forEach { assertEquals(it, DocumentationLink.BLOCKED, documentationLink(it)) }
    }
}
