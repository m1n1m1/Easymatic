package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.service.IntentExtra
import com.example.ottomatic.core.service.IntentTarget
import com.example.ottomatic.core.service.IntentValue
import com.example.ottomatic.core.service.LaunchOutcome
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.send_intent` — what reaches the platform, and what is refused before it gets there.
 *
 * The load-bearing assertions are the two halves of "an intent that does nothing looks exactly
 * like one that works": a config that cannot possibly launch must never reach `startActivity`,
 * and a launch Android blocked must be reported as blocked. The second is
 * `LaunchAppActionTest`'s regression re-pinned here, because a second node reaching the same
 * platform seam must not be able to describe that failure differently.
 */
class SendIntentActionTest {

    private val services = RecordingSystemServices()
    private val entries = mutableListOf<LogEntry>()
    private val context = DefaultExecutionContext(systemServices = services) { entries += it }
    private val action = SendIntentAction()

    private fun errors() = entries.filter { it.level == LogLevel.ERROR }.map { it.message }

    private fun warnings() = entries.filter { it.level == LogLevel.WARN }.map { it.message }

    @Test
    fun `a configured intent reaches the platform whole`() = runBlocking {
        action.execute(
            IntentConfig(
                action = "android.intent.action.SEND",
                packageName = "com.example.target",
                dataUri = "content://media/external/images/media/9",
                mimeType = "image/jpeg",
                category = "android.intent.category.DEFAULT",
                extras = "android.intent.extra.SUBJECT=Look\nandroid.intent.extra.INDEX:int=3",
            ),
            context,
        )

        val recipe = services.sentIntents.single()
        assertEquals(IntentTarget.ACTIVITY, recipe.target)
        assertEquals("android.intent.action.SEND", recipe.action)
        assertEquals("com.example.target", recipe.packageName)
        assertEquals("content://media/external/images/media/9", recipe.data)
        assertEquals("image/jpeg", recipe.mimeType)
        assertEquals("android.intent.category.DEFAULT", recipe.category)
        assertEquals(
            listOf(
                IntentExtra("android.intent.extra.SUBJECT", IntentValue.Text("Look")),
                IntentExtra("android.intent.extra.INDEX", IntentValue.Int32(3)),
            ),
            recipe.extras,
        )
        assertTrue("an intent that went out has nothing to report", errors().isEmpty())
    }

    @Test
    fun `an unconfigured action never reaches the platform`() = runBlocking {
        action.execute(IntentConfig(action = "   "), context)

        assertTrue("a blank action must never be handed over", services.sentIntents.isEmpty())
        assertEquals(1, errors().size)
    }

    @Test
    fun `a data uri with no scheme never reaches the platform`() = runBlocking {
        action.execute(IntentConfig(action = "a.ACTION", dataUri = "example.com/thing"), context)

        assertTrue(services.sentIntents.isEmpty())
        assertTrue(errors().single().contains("example.com/thing"))
    }

    /**
     * The whole argument for typing extras: a dropped one has to be *said*, because the launch
     * around it succeeds and an app reading `getIntExtra` on a string sees only its default.
     */
    @Test
    fun `an unreadable extra is dropped, reported, and does not stop the rest`() = runBlocking {
        action.execute(
            IntentConfig(action = "a.ACTION", extras = "good=1\nbad:itn=2"),
            context,
        )

        assertEquals(
            listOf(IntentExtra("good", IntentValue.Text("1"))),
            services.sentIntents.single().extras,
        )
        assertTrue(warnings().single().contains("bad"))
        assertTrue("a dropped extra is not a failed launch", errors().isEmpty())
    }

    @Test
    fun `a blocked launch is reported as blocked, not as missing`() = runBlocking {
        services.launchOutcome = LaunchOutcome.Blocked

        action.execute(IntentConfig(action = "android.intent.action.VIEW"), context)

        val message = errors().single()
        assertTrue("the message must name the grant that fixes it", message.contains("Display over other apps"))
        assertFalse("a blocked launch is not a missing app", message.contains("not installed"))
    }

    @Test
    fun `an intent nothing handles names the action`() = runBlocking {
        services.launchOutcome = LaunchOutcome.NoHandler

        action.execute(IntentConfig(action = "com.example.NOPE"), context)

        assertTrue(errors().single().contains("com.example.NOPE"))
    }

    /**
     * Without this the node has no way to *offer* the grant: the amber card in the config form
     * is driven entirely by what the definition declares.
     */
    @Test
    fun `the node declares the overlay prerequisite`() {
        val definition = NodeTypeRegistry.byId(NodeTypeId("action.send_intent"))

        assertTrue(
            "Send Intent starts an Activity, so a background run fails without this grant",
            definition?.permissionRequirements.orEmpty().any { it.type == PrerequisiteType.OVERLAY },
        )
    }
}
