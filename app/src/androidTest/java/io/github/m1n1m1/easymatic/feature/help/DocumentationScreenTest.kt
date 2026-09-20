package io.github.m1n1m1.easymatic.feature.help

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.ui.theme.EasymaticTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Local HTML exercises the real WebView without making the test depend on the hosted site. */
class DocumentationScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private var webView: WebView? = null
    private val page = DocumentationPageState()
    private var closed = false
    private var browserOpened = false
    private var retries = 0

    @After
    fun releaseWebView() {
        compose.runOnIdle {
            (webView?.parent as? android.view.ViewGroup)?.removeView(webView)
            webView?.destroy()
        }
    }

    @Test
    fun unavailableViewerOffersBrowserAndClose() {
        showScreen()
        compose.onNodeWithText(text(R.string.help_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.help_browser)).performClick()
        compose.runOnIdle { assertTrue(browserOpened) }
        compose.onNodeWithContentDescription(text(R.string.help_close)).performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun pageFailureOffersRetryAndBrowser() {
        createWebView()
        page.failed = true
        showScreen()
        compose.onNodeWithText(text(R.string.help_load_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.help_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText(text(R.string.help_browser)).performClick()
        compose.runOnIdle { assertTrue(browserOpened) }
    }

    @Test
    fun backVisitsPreviousPageBeforeClosingButCloseExitsImmediately() {
        createWebView()
        showScreen()
        loadPage("first")
        loadPage("second")
        compose.runOnIdle {
            assertTrue(webView!!.canGoBack())
            compose.activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(closed)
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose.runOnUiThread { !webView!!.canGoBack() && page.progress == COMPLETE }
        }
        loadPage("third")
        compose.onNodeWithContentDescription(text(R.string.help_close)).performClick()
        compose.runOnIdle {
            assertTrue(closed)
            assertTrue(webView!!.canGoBack())
        }
    }

    @Test
    fun backWithoutWebHistoryClosesHelp() {
        createWebView()
        showScreen()
        loadPage("first")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertTrue(closed) }
    }

    private fun createWebView() {
        compose.runOnUiThread {
            webView = WebView(compose.activity).also { configureDocumentationView(it, page) { browserOpened = true } }
        }
    }

    private fun loadPage(name: String) {
        compose.runOnIdle {
            page.progress = 0
            val url = "$DOCUMENTATION_URL$name/"
            webView!!.loadDataWithBaseURL(url, "<html><body>$name</body></html>", "text/html", "UTF-8", url)
        }
        compose.waitUntil(TIMEOUT_MS) { compose.runOnUiThread { page.progress == COMPLETE } }
    }

    private fun showScreen() {
        compose.setContent {
            EasymaticTheme(darkTheme = true, dynamicColor = false) {
                DocumentationScreen(
                    webView = webView,
                    page = page,
                    onClose = { closed = true },
                    onBrowser = { browserOpened = true },
                    onRetry = { retries++ },
                )
            }
        }
    }

    private fun text(@StringRes id: Int) = compose.activity.getString(id)

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val COMPLETE = 100
    }
}
