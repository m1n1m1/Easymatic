package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.core.service.IntentExtra
import io.github.m1n1m1.easymatic.core.service.IntentTarget
import io.github.m1n1m1.easymatic.core.service.IntentValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading of the two intent nodes' config, which is where every mistake a user can make
 * has to be caught — because past this point an intent that says nothing and an intent that
 * says the wrong thing both look like a launch that succeeded.
 *
 * The load-bearing cases are the ones that **do not** degrade quietly: an unknown type and a
 * value of the wrong type are dropped *and reported*, where `PortSpec.parse` would read them as
 * `ANY` and carry on. That difference is deliberate and is pinned here, because "make this
 * consistent with PortSpec" is exactly the tidy-up somebody would otherwise make.
 */
class IntentSpecTest {

    private fun ready(action: String = "com.example.ACTION", extras: String = "") =
        IntentSpec.plan(IntentTarget.ACTIVITY, action, extras = extras) as IntentPlan.Ready

    private fun extrasOf(raw: String): List<IntentExtra> = ready(extras = raw).recipe.extras

    private fun notesOf(raw: String): List<String> = ready(extras = raw).notes

    // ---- the entry grammar, inherited verbatim from IntentRequests.extrasOf ----

    @Test
    fun `an untyped entry is a string`() {
        assertEquals(
            listOf(IntentExtra("title", IntentValue.Text("Hello world"))),
            extrasOf("title=Hello world"),
        )
    }

    @Test
    fun `a value may contain further equals signs`() {
        assertEquals(
            listOf(IntentExtra("q", IntentValue.Text("a=b=c"))),
            extrasOf("q=a=b=c"),
        )
    }

    @Test
    fun `an entry with no equals sign is dropped and reported`() {
        assertTrue(extrasOf("just some words").isEmpty())
        assertEquals(1, notesOf("just some words").size)
    }

    @Test
    fun `an entry with no key is dropped`() {
        assertTrue(extrasOf("=orphan").isEmpty())
        assertEquals(1, notesOf("=orphan").size)
    }

    @Test
    fun `blank lines and stray whitespace are not entries`() {
        assertEquals(
            listOf(IntentExtra("a", IntentValue.Text("1"))),
            extrasOf("\n   \r\n  a=1  \n\n"),
        )
        assertTrue(notesOf("\n   \n a=1 \n").isEmpty())
    }

    // ---- the types ----

    @Test
    fun `every type round-trips`() {
        assertEquals(
            listOf(
                IntentExtra("a", IntentValue.Text("x")),
                IntentExtra("b", IntentValue.Int32(5)),
                IntentExtra("c", IntentValue.Int64(90000)),
                IntentExtra("d", IntentValue.Float32(1.5f)),
                IntentExtra("e", IntentValue.Float64(0.25)),
                IntentExtra("f", IntentValue.Flag(true)),
                IntentExtra("g", IntentValue.UriRef("content://media/1")),
                IntentExtra("h", IntentValue.Texts(listOf("a@b.com", "c@d.com"))),
            ),
            extrasOf(
                """
                a:text=x
                b:int=5
                c:long=90000
                d:float=1.5
                e:double=0.25
                f:bool=true
                g:uri=content://media/1
                h:texts=a@b.com, c@d.com
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `type names are case-insensitive`() {
        assertEquals(listOf(IntentExtra("n", IntentValue.Int32(5))), extrasOf("n:INT=5"))
    }

    @Test
    fun `a dotted key keeps its dots and takes the type off the end`() {
        assertEquals(
            listOf(IntentExtra("com.termux.RUN_COMMAND_BACKGROUND", IntentValue.Flag(false))),
            extrasOf("com.termux.RUN_COMMAND_BACKGROUND:bool=false"),
        )
    }

    @Test
    fun `a colon in the value is not a type`() {
        assertEquals(
            listOf(IntentExtra("url", IntentValue.Text("https://example.com"))),
            extrasOf("url=https://example.com"),
        )
    }

    // ---- the deliberate departure from PortSpec.parse ----

    @Test
    fun `an unknown type is dropped and reported, not read as text`() {
        // PortSpec.parse would degrade this to ANY. Here degrading would put an extra named
        // "count:itn" on a real intent and hide the typo behind a launch that succeeded.
        assertTrue(extrasOf("count:itn=5").isEmpty())
        assertTrue(notesOf("count:itn=5").single().contains("itn"))
    }

    @Test
    fun `a value that is not of its type is dropped and reported`() {
        assertTrue(extrasOf("count:int=abc").isEmpty())
        assertTrue(notesOf("count:int=abc").single().contains("count"))
    }

    @Test
    fun `only true and false are booleans`() {
        assertTrue(extrasOf("f:bool=yes").isEmpty())
        assertTrue(extrasOf("f:bool=1").isEmpty())
        assertEquals(listOf(IntentExtra("f", IntentValue.Flag(false))), extrasOf("f:bool=FALSE"))
    }

    @Test
    fun `a uri extra with no scheme is dropped`() {
        assertTrue(extrasOf("s:uri=media/1").isEmpty())
        assertEquals(1, notesOf("s:uri=media/1").size)
    }

    @Test
    fun `one bad line does not take the good ones with it`() {
        val raw = "good=1\nbad:itn=2\nalso.good:int=3"
        assertEquals(
            listOf(
                IntentExtra("good", IntentValue.Text("1")),
                IntentExtra("also.good", IntentValue.Int32(3)),
            ),
            extrasOf(raw),
        )
        assertEquals(1, notesOf(raw).size)
    }

    @Test
    fun `extras are capped and the cap is announced`() {
        val raw = (1..IntentSpec.MAX_EXTRAS + 5).joinToString("\n") { "k$it=$it" }
        assertEquals(IntentSpec.MAX_EXTRAS, extrasOf(raw).size)
        assertTrue(notesOf(raw).single().contains("${IntentSpec.MAX_EXTRAS}"))
    }

    // ---- the two refusals ----

    @Test
    fun `a blank action is refused`() {
        assertTrue(IntentSpec.plan(IntentTarget.ACTIVITY, "   ") is IntentPlan.Refused)
    }

    @Test
    fun `a data uri with no scheme is refused`() {
        val plan = IntentSpec.plan(IntentTarget.ACTIVITY, "a.ACTION", data = "example.com/x")
        assertTrue(plan is IntentPlan.Refused)
        assertTrue((plan as IntentPlan.Refused).reason.contains("example.com/x"))
    }

    @Test
    fun `a data uri that names a scheme is kept verbatim`() {
        // Every one of these would be mangled or refused by WebUrl.normalize's rules, which is
        // why this field deliberately does not go through it.
        for (uri in listOf("content://media/1", "package:com.foo", "tel:+43123", "geo:47.07,15.44")) {
            assertEquals(uri, (IntentSpec.plan(IntentTarget.ACTIVITY, "a", data = uri) as IntentPlan.Ready).recipe.data)
        }
    }

    @Test
    fun `a host with a port is not a scheme`() {
        assertTrue(IntentSpec.plan(IntentTarget.ACTIVITY, "a", data = "localhost:3000") is IntentPlan.Refused)
    }

    // ---- the implicit-broadcast note ----

    @Test
    fun `a broadcast with no app named is sent with a warning`() {
        val plan = IntentSpec.plan(IntentTarget.BROADCAST, "com.example.ACTION") as IntentPlan.Ready
        assertEquals(1, plan.notes.size)
        assertTrue(plan.notes.single().contains("Android 8"))
    }

    @Test
    fun `a broadcast that names an app says nothing`() {
        val plan = IntentSpec.plan(IntentTarget.BROADCAST, "com.example.ACTION", packageName = "com.foo")
        assertTrue((plan as IntentPlan.Ready).notes.isEmpty())
    }

    @Test
    fun `an activity with no app named says nothing`() {
        // The note is about broadcast delivery specifically. An implicit activity start is the
        // normal, correct way to open a URL or a document, and warning about it would be noise.
        assertTrue(ready().notes.isEmpty())
    }

    @Test
    fun `every field is trimmed on the way through`() {
        val plan = IntentSpec.plan(
            IntentTarget.ACTIVITY,
            action = "  a.ACTION  ",
            packageName = "  com.foo  ",
            data = "  https://x  ",
            mimeType = "  text/plain  ",
            category = "  a.CATEGORY  ",
        ) as IntentPlan.Ready
        assertEquals("a.ACTION", plan.recipe.action)
        assertEquals("com.foo", plan.recipe.packageName)
        assertEquals("https://x", plan.recipe.data)
        assertEquals("text/plain", plan.recipe.mimeType)
        assertEquals("a.CATEGORY", plan.recipe.category)
    }
}
