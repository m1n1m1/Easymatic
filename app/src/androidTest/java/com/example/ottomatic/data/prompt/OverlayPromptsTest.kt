package com.example.ottomatic.data.prompt

import android.content.Context
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ottomatic.core.service.PromptAnswer
import com.example.ottomatic.core.service.PromptRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real renderer, which only exists on a device: it puts a window on screen
 * through the platform's WindowManager, so none of this can run on the JVM.
 * `DialogActionsTest` covers everything on the graph's side of the boundary with
 * the renderer faked out; what is left is whether a window can actually be opened
 * from a background process, and whether it goes away again.
 *
 * The permission cannot be granted from a test — it is a Settings page, not a
 * runtime dialog — so the tests that need it are skipped rather than failed where
 * it is missing. Grant it by hand (Settings → Apps → Ottomatic → Display over
 * other apps) before running them; the one test that asserts the *absence* of the
 * permission is skipped in the other direction, so the file is useful either way.
 */
@RunWith(AndroidJUnit4::class)
class OverlayPromptsTest {

    private lateinit var context: Context
    private lateinit var prompts: OverlayPrompts

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        prompts = OverlayPrompts(context)
    }

    @Test
    fun reportsUnavailableWithoutThePermission() {
        assumeTrue("Overlay permission is granted here", !Settings.canDrawOverlays(context))
        val answer = runBlocking { prompts.ask(request()) }
        assertTrue("expected Unavailable, got $answer", answer is PromptAnswer.Unavailable)
    }

    @Test
    fun aCancelledCallerTakesTheWindowDownAndFreesTheNext() = runBlocking {
        assumeOverlayPermission()
        // The macro's own timeout is a cancellation of this call, so this is the
        // path every unanswered dialog takes. If the window survived it, or the
        // mutex stayed held, the *next* question would never be asked — which is
        // the failure that would be invisible until a second macro ran.
        val timedOut = withTimeoutOrNull(TIMEOUT_MS) { prompts.ask(request()) }
        assertNull("nobody answered, so this must give up", timedOut)

        val second = async(Dispatchers.Default) { prompts.ask(request("Second")) }
        delay(SETTLE_MS)
        assertTrue("the renderer stayed locked after a cancelled question", !second.isCompleted)
        second.cancel()
    }

    @Test
    fun showsOneAtATime() = runBlocking {
        assumeOverlayPermission()
        val first = async(Dispatchers.Default) { prompts.ask(request("First")) }
        val second = async(Dispatchers.Default) { prompts.ask(request("Second")) }
        delay(SETTLE_MS)
        // Neither has been answered, so both are outstanding; what matters is that
        // the second is queued rather than stacked on top of the first.
        assertEquals(1, withContext(Dispatchers.Main) { openDialogs() })
        first.cancel()
        second.cancel()
        delay(SETTLE_MS)
        assertEquals(0, withContext(Dispatchers.Main) { openDialogs() })
    }

    /**
     * How many of our overlay windows are on screen.
     *
     * There is no public API for "is a dialog showing", and the renderer
     * deliberately keeps no state to ask — it holds a continuation, not a handle.
     * Counting through the instrumentation's own view of the process is the honest
     * measurement, and it is what tells a queued dialog from a stacked one.
     */
    private fun openDialogs(): Int =
        InstrumentationRegistry.getInstrumentation().uiAutomation.windows
            .count { it.root?.packageName == context.packageName }

    private fun assumeOverlayPermission() =
        assumeTrue("Grant 'Display over other apps' to run this", Settings.canDrawOverlays(context))

    private fun request(title: String = "Ottomatic") = PromptRequest(
        title = title,
        message = "Testing",
        confirmLabel = "OK",
        cancelLabel = "Cancel",
    )

    private companion object {
        const val TIMEOUT_MS = 1_500L
        const val SETTLE_MS = 750L
    }
}
