package io.github.m1n1m1.easymatic.feature.help

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.ui.theme.EasymaticTheme

/** A separate activity leaves the caller's composition and editor viewport in place. */
class DocumentationActivity : AppCompatActivity() {
    private var webView: WebView? = null
    private val page = DocumentationPageState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        createViewer(savedInstanceState?.getBundle(WEB_STATE))
        setContent {
            EasymaticTheme(darkTheme = true, dynamicColor = false) {
                DocumentationScreen(
                    webView = webView,
                    page = page,
                    onClose = ::finish,
                    onBrowser = { openExternal(page.url) },
                    onRetry = {
                        page.failed = false
                        webView?.loadUrl(page.url)
                    },
                )
            }
        }
    }

    private fun createViewer(savedState: Bundle?) {
        try {
            webView = WebView(this)
            webView?.let { view ->
                configureDocumentationView(view, page, ::openExternal)
                val restored = savedState?.let(view::restoreState)
                if (restored == null) {
                    view.loadUrl(DOCUMENTATION_URL)
                } else {
                    page.url = view.url?.takeIf { documentationLink(it) == DocumentationLink.INTERNAL }
                        ?: DOCUMENTATION_URL
                }
            }
        } catch (_: RuntimeException) {
            // A disabled or missing WebView provider must still offer the browser escape hatch.
            webView?.destroy()
            webView = null
            page.failed = true
        }
    }

    private fun openExternal(url: String) {
        if (documentationLink(url) == DocumentationLink.BLOCKED) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.help_no_browser, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.help_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.let { view -> outState.putBundle(WEB_STATE, Bundle().also { view.saveState(it) }) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView?.stopLoading()
        (webView?.parent as? android.view.ViewGroup)?.removeView(webView)
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private companion object {
        const val WEB_STATE = "documentationWebState"
    }
}
